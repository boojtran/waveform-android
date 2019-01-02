package com.semantive.waveformandroid.waveform;

import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AbsoluteLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.semantive.waveformandroid.R;
import com.semantive.waveformandroid.waveform.soundfile.SamplePlayer;
import com.semantive.waveformandroid.waveform.soundfile.SoundFile;
import com.semantive.waveformandroid.waveform.view.MarkerView;
import com.semantive.waveformandroid.waveform.view.WaveformView;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.util.List;

import cn.pedant.SweetAlert.SweetAlertDialog;

import static android.app.Activity.RESULT_CANCELED;

/*
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Keeps track of the waveform display, current horizontal offset, marker handles,
 * start / end text boxes, and handles all of the buttons and controls
 * <p>
 * Modified by Anna Stępień <anna.stepien@semantive.com>
 */
public abstract class WaveformFragment extends Fragment implements MarkerView.MarkerListener, WaveformView.WaveformListener {

    public static final String TAG = "WaveformFragment";

    protected long mLoadingLastUpdateTime;
    protected boolean mLoadingKeepGoing;
    protected SweetAlertDialog mProgressDialog;
    protected SoundFile mSoundFile;
    protected File mFile;
    protected String mFilename;
    protected String imagesPath;
    protected WaveformView mWaveformView;
    protected MarkerView mStartMarker;
    protected MarkerView mEndMarker;
    protected TextView mStartText;
    protected TextView mEndText;
    protected TextView mInfo;
    protected ImageButton mPlayButton;
    protected ImageButton mRewindButton;
    protected ImageButton m10RewindButton;
    protected ImageButton mFfwdButton;
    protected ImageButton m10FfwdButton;
    protected boolean mKeyDown;
    protected String mCaption = "";
    protected int mWidth;
    protected int mMaxPos;
    protected int mStartPos;
    protected int mEndPos;
    protected boolean mStartVisible;
    protected boolean mEndVisible;
    protected int mLastDisplayedStartPos;
    protected int mLastDisplayedEndPos;
    protected int mOffset;
    protected int mOffsetGoal;
    protected int mFlingVelocity;
    protected int mPlayStartMsec;
    protected int mPlayStartOffset;
    protected int mPlayEndMsec;
    protected Handler mHandler;
    protected boolean mIsPlaying;
    protected SamplePlayer mPlayer;
    protected boolean mTouchDragging;
    protected float mTouchStart;
    protected int mTouchInitialOffset;
    protected int mTouchInitialStartPos;
    protected int mTouchInitialEndPos;
    protected long mWaveformTouchStartMsec;
    protected float mDensity;
    protected int mMarkerLeftInset;
    protected int mMarkerRightInset;
    protected int mMarkerTopOffset;
    protected int mMarkerBottomOffset;
    protected ImageView screen;
    protected int lastPos = -1;
    protected Drawable mBackground;
    private int mNewFileKind;
    private Thread mSaveSoundFileThread;

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View view = inflater.inflate(R.layout.fragment_waveform, container, false);
        loadGui(view);
        if (mSoundFile == null) {
            loadFromFile();
        } else {
            mHandler.post(() -> finishOpeningSoundFile());
        }
        return view;
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setRetainInstance(true);

        mPlayer = null;
        mIsPlaying = false;

        mFilename = getFileName();
        imagesPath = getImagesPath();
        mBackground = getBackground();
        mSoundFile = null;
        mSaveSoundFileThread = null;
        mKeyDown = false;

        mHandler = new Handler();

        mHandler.postDelayed(mTimerRunnable, 100);
    }

    @Override
    public void onDestroy() {
        if (mPlayer != null && mPlayer.isPlaying()) {
            mPlayer.stop();
            mPlayer.release();
            mPlayer = null;
        }
        closeThread(mSaveSoundFileThread);

        mSoundFile = null;
        mSaveSoundFileThread = null;
        mWaveformView = null;
        super.onDestroy();
    }

    private void closeThread(Thread thread) {
        if (thread != null && thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
            }
        }
    }

    //
    // WaveformListener
    //

    /**
     * Every time we get a message that our waveform drew, see if we need to
     * animate and trigger another redraw.
     */
    public void waveformDraw() {
        mWidth = mWaveformView.getMeasuredWidth();
        if (mOffsetGoal != mOffset && !mKeyDown)
            updateDisplay();
        else if (mIsPlaying) {
            updateDisplay();
        } else if (mFlingVelocity != 0) {
            updateDisplay();
        }
    }

    public void waveformTouchStart(float x) {
        mTouchDragging = true;
        mTouchStart = x;
        mTouchInitialOffset = mOffset;
        mFlingVelocity = 0;
        mWaveformTouchStartMsec = System.currentTimeMillis();
    }

    public void waveformTouchMove(float x) {
        mOffset = trap((int) (mTouchInitialOffset + (mTouchStart - x)));
        updateDisplay();
    }

    public void waveformTouchEnd() {
        mTouchDragging = false;
        mOffsetGoal = mOffset;

        long elapsedMsec = System.currentTimeMillis() - mWaveformTouchStartMsec;
        if (elapsedMsec < 300) {
            if (mIsPlaying) {
                int seekMsec = mWaveformView.pixelsToMillisecs((int) (mTouchStart + mOffset));
                if (seekMsec >= mPlayStartMsec && seekMsec < mPlayEndMsec) {
                    mPlayer.seekTo(seekMsec - mPlayStartOffset);
                } else {
                    handlePause();
                }
            } else {
                onPlay((int) (mTouchStart + mOffset));
            }
        }
    }

    public void waveformFling(float vx) {
        mTouchDragging = false;
        mOffsetGoal = mOffset;
        mFlingVelocity = (int) (-vx);
        updateDisplay();
    }

    public void waveformZoomIn() {
        mWaveformView.zoomIn();
        mStartPos = mWaveformView.getStart();
        mEndPos = mWaveformView.getEnd();
        mMaxPos = mWaveformView.maxPos();
        mOffset = mWaveformView.getOffset();
        mOffsetGoal = mOffset;
        enableZoomButtons();
        updateDisplay();
    }

    public void waveformZoomOut() {
//        mWaveformView.zoomOut();
//        mStartPos = mWaveformView.getStart();
//        mEndPos = mWaveformView.getEnd();
//        mMaxPos = mWaveformView.maxPos();
//        mOffset = mWaveformView.getOffset();
//        mOffsetGoal = mOffset;
//        enableZoomButtons();
//        updateDisplay();
    }

    //
    // MarkerListener
    //

    public void markerDraw() {
    }

    public void markerTouchStart(MarkerView marker, float x) {
        mTouchDragging = true;
        mTouchStart = x;
        mTouchInitialStartPos = mStartPos;
        mTouchInitialEndPos = mEndPos;
    }

    public void markerTouchMove(MarkerView marker, float x) {
        float delta = x - mTouchStart;

        if (marker == mStartMarker) {
            mStartPos = trap((int) (mTouchInitialStartPos + delta));
//            mEndPos = trap((int) (mTouchInitialEndPos + delta));
        }
//        else
//            {
//            mEndPos = trap((int) (mTouchInitialEndPos + delta));
//            if (mEndPos < mStartPos)
//                mEndPos = mStartPos;
//        }

        updateDisplay();
    }

    public void markerTouchEnd(MarkerView marker) {
        mTouchDragging = false;
        if (marker == mStartMarker) {
            setOffsetGoalStart();
        }
//        else {
//            setOffsetGoalEnd();
//        }
    }

    public void markerLeft(MarkerView marker, int velocity) {
        mKeyDown = true;

        if (marker == mStartMarker) {
            int saveStart = mStartPos;
            mStartPos = trap(mStartPos - velocity);
//            mEndPos = trap(mEndPos - (saveStart - mStartPos));
            setOffsetGoalStart();
        }

//        if (marker == mEndMarker) {
//            if (mEndPos == mStartPos) {
//                mStartPos = trap(mStartPos - velocity);
//                mEndPos = mStartPos;
//            } else {
//                mEndPos = trap(mEndPos - velocity);
//            }
//
//            setOffsetGoalEnd();
//        }

        updateDisplay();
    }

    public void markerRight(MarkerView marker, int velocity) {
        mKeyDown = true;

        if (marker == mStartMarker) {
            int saveStart = mStartPos;
            mStartPos += velocity;
            if (mStartPos > mMaxPos)
                mStartPos = mMaxPos;
//            mEndPos += (mStartPos - saveStart);
//            if (mEndPos > mMaxPos)
//                mEndPos = mMaxPos;

            setOffsetGoalStart();
        }

//        if (marker == mEndMarker) {
//            mEndPos += velocity;
//            if (mEndPos > mMaxPos)
//                mEndPos = mMaxPos;
//
//            setOffsetGoalEnd();
//        }

        updateDisplay();
    }

    public void markerEnter(MarkerView marker) {
    }

    public void markerKeyUp() {
        mKeyDown = false;
        updateDisplay();
    }

    public void markerFocus(MarkerView marker) {
        mKeyDown = false;
        if (marker == mStartMarker) {
            setOffsetGoalStartNoUpdate();
        }
//        else {
//            setOffsetGoalEndNoUpdate();
//        }

        // Delay updaing the display because if this focus was in
        // response to a touch event, we want to receive the touch
        // event too before updating the display.
        mHandler.postDelayed(() -> updateDisplay(), 100);
    }

    //
    // Internal methods
    //

    protected void loadGui(View view) {
        DisplayMetrics metrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mDensity = metrics.density;

        mMarkerLeftInset = (int) (46 * mDensity);
        mMarkerRightInset = (int) (48 * mDensity);
        mMarkerTopOffset = (int) (10 * mDensity);
        mMarkerBottomOffset = (int) (10 * mDensity);

        mStartText = (TextView) view.findViewById(R.id.starttext);
        mStartText.addTextChangedListener(mTextWatcher);
        mEndText = (TextView) view.findViewById(R.id.endtext);
        mEndText.addTextChangedListener(mTextWatcher);

        mPlayButton = (ImageButton) view.findViewById(R.id.play);
        mPlayButton.setOnClickListener(mPlayListener);
        mRewindButton = (ImageButton) view.findViewById(R.id.rew);
        m10RewindButton = (ImageButton) view.findViewById(R.id.rew10);
        mRewindButton.setOnClickListener(getRewindListener());
        m10RewindButton.setOnClickListener(get10RewindListener());

        mFfwdButton = (ImageButton) view.findViewById(R.id.ffwd);
        m10FfwdButton = (ImageButton) view.findViewById(R.id.ffwd10);
        mFfwdButton.setOnClickListener(getFwdListener());
        m10FfwdButton.setOnClickListener(get10FwdListener());

        TextView markStartButton = (TextView) view.findViewById(R.id.mark_start);
        markStartButton.setOnClickListener(mMarkStartListener);
        TextView markEndButton = (TextView) view.findViewById(R.id.mark_end);
        markEndButton.setOnClickListener(mMarkEndListener);

        enableDisableButtons();

        mWaveformView = (WaveformView) view.findViewById(R.id.waveform);
        mWaveformView.setListener(this);
        mWaveformView.setSegments(getSegments());

        mInfo = (TextView) view.findViewById(R.id.info);
        mInfo.setText(mCaption);

        mMaxPos = 0;
        mLastDisplayedStartPos = -1;
        mLastDisplayedEndPos = -1;

        if (mSoundFile != null && !mWaveformView.hasSoundFile()) {
            mWaveformView.setSoundFile(mSoundFile);
            mWaveformView.recomputeHeights(mDensity);
            mMaxPos = mWaveformView.maxPos();
        }

        mStartMarker = (MarkerView) view.findViewById(R.id.startmarker);
        mStartMarker.setListener(this);
        mStartMarker.setImageAlpha(255);
        mStartMarker.setFocusable(true);
        mStartMarker.setFocusableInTouchMode(true);
        mStartVisible = true;

        mEndMarker = (MarkerView) view.findViewById(R.id.endmarker);
        mEndMarker.setListener(this);
        mEndMarker.setImageAlpha(255);
        mEndMarker.setFocusable(true);
        mEndMarker.setFocusableInTouchMode(true);
        mEndVisible = true;
        screen = view.findViewById(R.id.screen);

        if (mBackground != null)
            screen.setBackground(mBackground);
        updateDisplay();
    }

    protected void loadFromFile() {
        mFile = new File(mFilename);
        mLoadingLastUpdateTime = System.currentTimeMillis();
        mLoadingKeepGoing = true;
        Context context = getContext();
        if (context != null)
            mProgressDialog = new SweetAlertDialog(context, SweetAlertDialog.PROGRESS_TYPE);
        mProgressDialog.setTitleText(R.string.progress_dialog_loading)
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        mLoadingKeepGoing = false;
                    }
                });
        mProgressDialog.show();

        final int[] i = {0};
        final SoundFile.ProgressListener listener = (double fractionComplete) -> {
            long now = System.currentTimeMillis();
            if (now - mLoadingLastUpdateTime > 100) {
                if ((i[0] > 6)) {
                    i[0] = 0;
                } else {
                    i[0]++;
                }
                switch (i[0]) {
                    case 0:
                        mProgressDialog.getProgressHelper().setBarColor(getResources().getColor(R.color.blue_btn_bg_color));
                        break;
                    case 1:
                        mProgressDialog.getProgressHelper().setBarColor(getResources().getColor(R.color.material_deep_teal_50));
                        break;
                    case 2:
                        mProgressDialog.getProgressHelper().setBarColor(getResources().getColor(R.color.success_stroke_color));
                        break;
                    case 3:
                        mProgressDialog.getProgressHelper().setBarColor(getResources().getColor(R.color.material_deep_teal_20));
                        break;
                    case 4:
                        mProgressDialog.getProgressHelper().setBarColor(getResources().getColor(R.color.material_blue_grey_80));
                        break;
                    case 5:
                        mProgressDialog.getProgressHelper().setBarColor(getResources().getColor(R.color.warning_stroke_color));
                        break;
                    case 6:
                        mProgressDialog.getProgressHelper().setBarColor(getResources().getColor(R.color.success_stroke_color));
                        break;
                }
                mLoadingLastUpdateTime = now;
            }
            return mLoadingKeepGoing;
        };

        // Load the sound file in a background thread
        new Thread() {
            public void run() {
                try {
                    mSoundFile = SoundFile.create(mFile.getAbsolutePath(), listener);
                    mPlayer = new SamplePlayer(mSoundFile);
                } catch (final Exception e) {
                    Log.e(TAG, "Error while loading sound file", e);
                    mProgressDialog.dismissWithAnimation();
                    mInfo.setText(e.toString());
                    return;
                }
                if (mLoadingKeepGoing) {
                    mHandler.post(() -> finishOpeningSoundFile());
                }
            }
        }.start();
    }

    protected void finishOpeningSoundFile() {
        mWaveformView.setSoundFile(mSoundFile);
        mWaveformView.recomputeHeights(mDensity);

        mMaxPos = mWaveformView.maxPos();
        mLastDisplayedStartPos = -1;
        mLastDisplayedEndPos = -1;

        mTouchDragging = false;

        mOffset = 0;
        mOffsetGoal = 0;
        mFlingVelocity = 0;
        resetPositions();

        mCaption = mSoundFile.getFiletype() + ", " +
                mSoundFile.getSampleRate() + " Hz, " +
                mSoundFile.getAvgBitrateKbps() + " kbps, " +
                formatTime(mMaxPos) + " " + getResources().getString(R.string.time_seconds);
        mInfo.setText(mCaption);
        mProgressDialog.dismissWithAnimation();
        updateDisplay();
    }

    protected synchronized void updateDisplay() {
        if (mIsPlaying) {
            int now = mPlayer.getCurrentPosition() + mPlayStartOffset;
            if (lastPos != now / 1000) {
                lastPos = now / 1000;
                Bitmap bitmap = BitmapFactory.decodeFile(imagesPath + convertId(lastPos) + ".webp");
                screen.setImageBitmap(bitmap);
            }

            int frames = mWaveformView.millisecsToPixels(now);
            mWaveformView.setPlayback(frames);
            setOffsetGoalNoUpdate(frames - mWidth + 10);
            if (now >= mPlayEndMsec) {
                handlePause();
            }
        }

        if (!mTouchDragging) {
            int offsetDelta;

            if (mFlingVelocity != 0) {
                float saveVel = mFlingVelocity;

                offsetDelta = mFlingVelocity / 30;
                if (mFlingVelocity > 80) {
                    mFlingVelocity -= 80;
                } else if (mFlingVelocity < -80) {
                    mFlingVelocity += 80;
                } else {
                    mFlingVelocity = 0;
                }

                mOffset += offsetDelta;

                if (mOffset + mWidth - 10 > mMaxPos) {
                    mOffset = mMaxPos - mWidth;
                    mFlingVelocity = 0;
                }
                if (mOffset < 0) {
                    mOffset = 0;
                    mFlingVelocity = 0;
                }
                mOffsetGoal = mOffset;
            } else {
                offsetDelta = mOffsetGoal - mOffset;

                if (offsetDelta > 10)
                    offsetDelta = offsetDelta / 10;
                else if (offsetDelta > 0)
                    offsetDelta = 1;
                else if (offsetDelta < -10)
                    offsetDelta = offsetDelta / 10;
                else if (offsetDelta < 0)
                    offsetDelta = -1;
                else
                    offsetDelta = 0;

                mOffset += offsetDelta;
            }
        }

        mWaveformView.setParameters(mStartPos, mEndPos, mOffset);
        mWaveformView.invalidate();

        mStartMarker.setContentDescription(getResources().getText(R.string.start_marker) + " " + formatTime(mStartPos));
        mEndMarker.setContentDescription(getResources().getText(R.string.end_marker) + " " + formatTime(mEndPos));

        int startX = mStartPos - mOffset - mMarkerLeftInset;
        if (startX + mStartMarker.getWidth() >= 0) {
            if (!mStartVisible) {
                // Delay this to avoid flicker
                mHandler.postDelayed(() -> {
                    mStartVisible = true;
                    mStartMarker.setImageAlpha(255);
                }, 0);
            }
        } else {
            if (mStartVisible) {
                mStartMarker.setImageAlpha(0);
                mStartVisible = false;
            }
            startX = 0;
        }

//        int endX = mEndPos - mOffset - mEndMarker.getWidth() + mMarkerRightInset;
//        if (endX + mEndMarker.getWidth() >= 0) {
//            if (!mEndVisible) {
//                // Delay this to avoid flicker
//                mHandler.postDelayed(() -> {
//                    mEndVisible = true;
//                    mEndMarker.setImageAlpha(255);
//                }, 0);
//            }
//        } else {
//            if (mEndVisible) {
//                mEndMarker.setImageAlpha(0);
//                mEndVisible = false;
//            }
//            endX = 0;
//        }

        Log.d("MrxXxc", "startX = " + startX + " mMarkerTopOffset = " + mMarkerTopOffset);
        mStartMarker.setLayoutParams(
                new AbsoluteLayout.LayoutParams(
                        64,
                        AbsoluteLayout.LayoutParams.WRAP_CONTENT,
                        startX + 32, mMarkerTopOffset));

//        mEndMarker.setLayoutParams(
//                new AbsoluteLayout.LayoutParams(
//                        AbsoluteLayout.LayoutParams.WRAP_CONTENT,
//                        AbsoluteLayout.LayoutParams.WRAP_CONTENT,
//                        endX, mWaveformView.getMeasuredHeight() - mEndMarker.getHeight() - mMarkerBottomOffset));
    }

    protected Runnable mTimerRunnable = new Runnable() {
        public void run() {
            // Updating an EditText is slow on Android.  Make sure
            // we only do the update if the text has actually changed.
            if (mStartPos != mLastDisplayedStartPos && !mStartText.hasFocus()) {
                mStartText.setText(formatTime(mStartPos));
                mLastDisplayedStartPos = mStartPos;
            }

            if (mEndPos != mLastDisplayedEndPos && !mEndText.hasFocus()) {
                mEndText.setText(formatTime(mEndPos));
                mLastDisplayedEndPos = mEndPos;
            }

            mHandler.postDelayed(mTimerRunnable, 100);
        }
    };

    protected void enableDisableButtons() {
        if (mIsPlaying) {
            mPlayButton.setImageResource(android.R.drawable.ic_media_pause);
            mPlayButton.setContentDescription(getResources().getText(R.string.stop));
        } else {
            mPlayButton.setImageResource(android.R.drawable.ic_media_play);
            mPlayButton.setContentDescription(getResources().getText(R.string.play));
        }
    }

    protected void resetPositions() {
        mStartPos = mMaxPos;
        mEndPos = mMaxPos;
    }

    protected int trap(int pos) {
        if (pos < 0)
            return 0;
        if (pos > mMaxPos)
            return mMaxPos;
        return pos;
    }

    protected void setOffsetGoalStart() {
        setOffsetGoal(mStartPos - mWidth);
    }

    protected void setOffsetGoalStartNoUpdate() {
        setOffsetGoalNoUpdate(mStartPos - mWidth);
    }

    protected void setOffsetGoalEnd() {
        setOffsetGoal(mEndPos - mWidth);
    }

    protected void setOffsetGoalEndNoUpdate() {
        setOffsetGoalNoUpdate(mEndPos - mWidth);
    }

    protected void setOffsetGoal(int offset) {
        setOffsetGoalNoUpdate(offset);
        updateDisplay();
    }

    protected void setOffsetGoalNoUpdate(int offset) {
        if (mTouchDragging) {
            return;
        }

        mOffsetGoal = offset;
        if (mOffsetGoal + mWidth > mMaxPos)
            mOffsetGoal = mMaxPos - mWidth;
        if (mOffsetGoal < 0)
            mOffsetGoal = 0;
    }

    protected String formatTime(int pixels) {
        if (mWaveformView != null && mWaveformView.isInitialized()) {
            return formatDecimal(mWaveformView.pixelsToSeconds(pixels));
        } else {
            return "";
        }
    }

    protected String formatDecimal(double x) {
        int xWhole = (int) x;
        int xFrac = (int) (100 * (x - xWhole) + 0.5);

        if (xFrac >= 100) {
            xWhole++; //Round up
            xFrac -= 100; //Now we need the remainder after the round up
            if (xFrac < 10) {
                xFrac *= 10; //we need a fraction that is 2 digits long
            }
        }

        if (xFrac < 10)
            return xWhole + ".0" + xFrac;
        else
            return xWhole + "." + xFrac;
    }

    protected synchronized void handlePause() {
        if (mPlayer != null && mPlayer.isPlaying()) {
            mPlayer.pause();
        }
        mWaveformView.setPlayback(-1);
        mIsPlaying = false;
        enableDisableButtons();
    }

    protected synchronized void onPlay(int startPosition) {
        if (mIsPlaying) {
            handlePause();
            return;
        }

        if (mPlayer == null) {
            // Not initialized yet
            return;
        }

        try {
            mPlayStartMsec = mWaveformView.pixelsToMillisecs(startPosition);
            if (startPosition < mStartPos) {
                mPlayEndMsec = mWaveformView.pixelsToMillisecs(mStartPos);
            } else if (startPosition > mEndPos) {
                mPlayEndMsec = mWaveformView.pixelsToMillisecs(mMaxPos);
            } else {
                mPlayEndMsec = mWaveformView.pixelsToMillisecs(mEndPos);
            }
            mPlayer.setOnCompletionListener(() -> handlePause());
            mIsPlaying = true;

            mPlayer.seekTo(mPlayStartMsec);
            mPlayer.start();
            updateDisplay();
            enableDisableButtons();
        } catch (Exception e) {
            Log.e(TAG, "Error while playing sound file", e);
            mInfo.setText(e.toString());
        }
    }

    protected void enableZoomButtons() {
    }

    public void onSave(final double endTime) {
        if (mIsPlaying) {
            handlePause();
        }
        Context context = getContext();
        if (context != null) {
            SweetAlertDialog dialog = new SweetAlertDialog(context)
                    .setTitleText(getString(R.string.are_you_sure))
                    .setConfirmText(getString(R.string.yes))
                    .setConfirmClickListener(new SweetAlertDialog.OnSweetClickListener() {
                        @Override
                        public void onClick(SweetAlertDialog sDialog) {
                            saveRingtone("file name", endTime);
                            sDialog.dismissWithAnimation();
                        }
                    })
                    .setCancelText(getString(R.string.no))
                    .setCancelClickListener(new SweetAlertDialog.OnSweetClickListener() {
                        @Override
                        public void onClick(SweetAlertDialog sweetAlertDialog) {
                            sweetAlertDialog.dismissWithAnimation();
                        }
                    });
            dialog.setOnShowListener(new DialogInterface.OnShowListener() {
                @Override
                public void onShow(DialogInterface dialog) {
                    SweetAlertDialog alertDialog = (SweetAlertDialog) dialog;
                    TextView text = (TextView) alertDialog.findViewById(R.id.title_text);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        text.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                    }
                    SpannableStringBuilder snapBuilder = new SpannableStringBuilder();
                    snapBuilder.append(getString(R.string.are_you_sure));
                    snapBuilder.setSpan(new StyleSpan(Typeface.BOLD), 0, snapBuilder.length(), 0);
                    snapBuilder.append("\n\n");
                    snapBuilder.append(getString(R.string.wont_be_able_to_recover_this_file));
                    text.setSingleLine(false);
                    text.setText(snapBuilder);
                }
            });
            dialog.show();
        }
    }

    private void saveRingtone(final CharSequence title, final double endTime) {
        double startTime = 0;
//        double endTime = mWaveformView.pixelsToSeconds(mStartPos);
        final int startFrame = mWaveformView.secondsToFrames(startTime);
        final int endFrame = mWaveformView.secondsToFrames(endTime);
        final int duration = (int) (endTime - startTime + 0.5);

        // Create an indeterminate progress dialog
        Context context = getContext();
        if (context != null)
            mProgressDialog = new SweetAlertDialog(context, SweetAlertDialog.PROGRESS_TYPE);
        mProgressDialog.setTitle(R.string.progress_dialog_saving);
        mProgressDialog.setCancelable(false);
        mProgressDialog.show();

        // Save the sound file in a background thread
        mSaveSoundFileThread = new Thread() {
            public void run() {
                // Try AAC first.
                String outPath = makeRingtoneFilename();
                if (outPath == null) {
                    Runnable runnable = new Runnable() {
                        public void run() {
                            showFinalAlert(new Exception(), "no unique file name");
                        }
                    };
                    mHandler.post(runnable);
                    return;
                }
                File outFile = new File(outPath);
                Boolean fallbackToWAV = false;
                try {
                    // Write the new file
                    mSoundFile.WriteFile(outFile, startFrame, endFrame - startFrame);
                } catch (Exception e) {
                    // log the error and try to create a .wav file instead
                    if (outFile.exists()) {
                        outFile.delete();
                    }
                    StringWriter writer = new StringWriter();
                    e.printStackTrace(new PrintWriter(writer));
                    Log.e("Ringdroid", "Error: Failed to create " + outPath);
                    Log.e("Ringdroid", writer.toString());
                    fallbackToWAV = true;
                }

                // Try to create a .wav file if creating a .m4a file failed.
                if (fallbackToWAV) {
                    outPath = makeRingtoneFilename();
                    if (outPath == null) {
                        Runnable runnable = new Runnable() {
                            public void run() {
                                showFinalAlert(new Exception(), "no_unique_filename");
                            }
                        };
                        mHandler.post(runnable);
                        return;
                    }
                    outFile = new File(outPath);
                    try {
                        // create the .wav file
                        mSoundFile.WriteWAVFile(outFile, startFrame, endFrame - startFrame);
                    } catch (Exception e) {
                        // Creating the .wav file also failed. Stop the progress dialog, show an
                        // error message and exit.
                        mProgressDialog.dismissWithAnimation();
                        if (outFile.exists()) {
                            outFile.delete();
                        }
//                        mInfoContent = e.toString();
//                        runOnUiThread(new Runnable() {
//                            public void run() {
//                                mInfo.setText(mInfoContent);
//                            }
//                        });

                        CharSequence errorMessage;
                        if (e.getMessage() != null
                                && e.getMessage().equals("No space left on device")) {
                            errorMessage = "no_space_error";
                            e = null;
                        } else {
                            errorMessage = "write_error";
                        }
                        final CharSequence finalErrorMessage = errorMessage;
                        final Exception finalException = e;
                        Runnable runnable = new Runnable() {
                            public void run() {
                                showFinalAlert(finalException, finalErrorMessage);
                            }
                        };
                        mHandler.post(runnable);
                        return;
                    }
                }

                // Try to load the new file to make sure it worked
                try {
                    final SoundFile.ProgressListener listener =
                            new SoundFile.ProgressListener() {
                                public boolean reportProgress(double frac) {
                                    // Do nothing - we're not going to try to
                                    // estimate when reloading a saved sound
                                    // since it's usually fast, but hard to
                                    // estimate anyway.
                                    return true;  // Keep going
                                }
                            };
                    SoundFile.create(outPath, listener);
                } catch (final Exception e) {
                    mProgressDialog.dismissWithAnimation();
                    e.printStackTrace();
//                    mInfoContent = e.toString();
//                    runOnUiThread(new Runnable() {
//                        public void run() {
//                            mInfo.setText(mInfoContent);
//                        }
//                    });

                    Runnable runnable = new Runnable() {
                        public void run() {
                            showFinalAlert(e, "write_error");
                        }
                    };
                    mHandler.post(runnable);
                    return;
                }

                mProgressDialog.dismissWithAnimation();

                final String finalOutPath = outPath;
                Runnable runnable = new Runnable() {
                    public void run() {
                        afterSavingRingtone(title,
                                finalOutPath,
                                duration);
                    }
                };
                mHandler.post(runnable);
            }
        };
        mSaveSoundFileThread.start();
    }

    private String makeRingtoneFilename() {

        String path = mFilename;
        try {
            RandomAccessFile f = new RandomAccessFile(new File(path), "r");
            f.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return path;
    }

    private void afterSavingRingtone(CharSequence title,
                                     String outPath,
                                     int duration) {
        File outFile = new File(outPath);
        long fileSize = outFile.length();
        if (fileSize <= 512) {
            outFile.delete();
            Context context = getContext();
            if (context != null) {
                SweetAlertDialog dialog = new SweetAlertDialog(context, SweetAlertDialog.ERROR_TYPE)
                        .setTitleText(getString(R.string.error))
                        .setConfirmText(getString(R.string.repeat))
                        .setConfirmClickListener(new SweetAlertDialog.OnSweetClickListener() {
                            @Override
                            public void onClick(SweetAlertDialog sDialog) {
                                sDialog.dismissWithAnimation();
                            }
                        });
                dialog.setOnShowListener(new DialogInterface.OnShowListener() {
                    @Override
                    public void onShow(DialogInterface dialog) {
                        SweetAlertDialog alertDialog = (SweetAlertDialog) dialog;
                        TextView text = (TextView) alertDialog.findViewById(R.id.title_text);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                            text.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                        }
                        SpannableStringBuilder snapBuilder = new SpannableStringBuilder();
                        snapBuilder.append(getString(R.string.error));
                        snapBuilder.setSpan(new StyleSpan(Typeface.BOLD), 0, snapBuilder.length(), 0);
                        snapBuilder.append("\n\n");
                        snapBuilder.append(getString(R.string.error_with_save_file));
                        text.setSingleLine(false);
                        text.setText(snapBuilder);
                    }
                });
                dialog.show();
            }
            return;
        }

        // Create the database record, pointing to the existing file path
        String mimeType;
        if (outPath.endsWith(".m4a")) {
            mimeType = "audio/mp4a-latm";
        } else if (outPath.endsWith(".wav")) {
            mimeType = "audio/wav";
        } else {
            // This should never happen.
            mimeType = "audio/mpeg";
        }

        String artist = "" + "artist_name";

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DATA, outPath);
        values.put(MediaStore.MediaColumns.TITLE, title.toString());
        values.put(MediaStore.MediaColumns.SIZE, fileSize);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);

        values.put(MediaStore.Audio.Media.ARTIST, artist);
        values.put(MediaStore.Audio.Media.DURATION, duration);

        values.put(MediaStore.Audio.Media.IS_RINGTONE,
                mNewFileKind == FileSaveDialog.FILE_KIND_RINGTONE);
        values.put(MediaStore.Audio.Media.IS_NOTIFICATION,
                mNewFileKind == FileSaveDialog.FILE_KIND_NOTIFICATION);
        values.put(MediaStore.Audio.Media.IS_ALARM,
                mNewFileKind == FileSaveDialog.FILE_KIND_ALARM);
        values.put(MediaStore.Audio.Media.IS_MUSIC,
                mNewFileKind == FileSaveDialog.FILE_KIND_MUSIC);

        // Insert it into the database
        Uri uri = MediaStore.Audio.Media.getContentUriForPath(outPath);
//        final Uri newUri = getContentResolver().insert(uri, values);
//        setResult(RESULT_OK, new Intent().setData(newUri));

        // If Ringdroid was launched to get content, just return
//        if (mWasGetContentIntent) {
//            finish();
//            return;
//        }

        // There's nothing more to do with music or an alarm.  Show a
        // success message and then quit.
        if (mNewFileKind == FileSaveDialog.FILE_KIND_MUSIC ||
                mNewFileKind == FileSaveDialog.FILE_KIND_ALARM) {
//            Toast.makeText(getContext(),
//                    "save_success_message",
//                    Toast.LENGTH_SHORT)
//                    .show();
            return;
        }

        // If it's a notification, give the user the option of making
        // this their default notification.  If they say no, we're finished.
        if (mNewFileKind == FileSaveDialog.FILE_KIND_NOTIFICATION) {
            new AlertDialog.Builder(getContext())
                    .setTitle("alert_title_success")
                    .setMessage("set_default_notification")
                    .setPositiveButton("yes",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                                    int whichButton) {
                                    RingtoneManager.setActualDefaultRingtoneUri(
                                            getContext(),
                                            RingtoneManager.TYPE_NOTIFICATION,
                                            Uri.fromFile(mFile));
                                }
                            })
                    .setNegativeButton(
                            "no",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int whichButton) {
                                    dialog.dismiss();
                                }
                            })
                    .setCancelable(false)
                    .show();
            return;
        }

        // If we get here, that means the type is a ringtone.  There are
        // three choices: make this your default ringtone, assign it to a
        // contact, or do nothing.
    }

    protected OnClickListener mPlayListener = new OnClickListener() {
        public void onClick(View sender) {
            onPlay(mStartPos);
        }
    };

    protected OnClickListener mRewindListener = new OnClickListener() {
        public void onClick(View sender) {
            if (mIsPlaying) {
                int newPos = mPlayer.getCurrentPosition() - 5000;
                if (newPos < mPlayStartMsec)
                    newPos = mPlayStartMsec;
                mPlayer.seekTo(newPos);
            } else {
                mStartPos = trap(mStartPos - mWaveformView.secondsToPixels(getStep(false)));
                updateDisplay();
                mStartMarker.requestFocus();
                markerFocus(mStartMarker);
            }
        }
    };

    protected OnClickListener m10RewindListener = new OnClickListener() {
        public void onClick(View sender) {
            if (mIsPlaying) {
                int newPos = mPlayer.getCurrentPosition() - 10_000;
                if (newPos < mPlayStartMsec)
                    newPos = mPlayStartMsec;
                mPlayer.seekTo(newPos);
            } else {
                mStartPos = trap(mStartPos - mWaveformView.secondsToPixels(getStep(true)));
                updateDisplay();
                mStartMarker.requestFocus();
                markerFocus(mStartMarker);
            }
        }
    };

    protected OnClickListener mFfwdListener = new OnClickListener() {
        public void onClick(View sender) {
            if (mIsPlaying) {
                int newPos = 5000 + mPlayer.getCurrentPosition();
                if (newPos > mPlayEndMsec)
                    newPos = mPlayEndMsec;
                mPlayer.seekTo(newPos);
            } else {
                mStartPos = trap(mStartPos + mWaveformView.secondsToPixels(getStep(false)));
                updateDisplay();
                mStartMarker.requestFocus();
                markerFocus(mStartMarker);
            }
        }
    };

    protected OnClickListener m10FfwdListener = new OnClickListener() {
        public void onClick(View sender) {
            if (mIsPlaying) {
                int newPos = 10_000 + mPlayer.getCurrentPosition();
                if (newPos > mPlayEndMsec)
                    newPos = mPlayEndMsec;
                mPlayer.seekTo(newPos);
            } else {
                mStartPos = trap(mStartPos + mWaveformView.secondsToPixels(getStep(true)));
                updateDisplay();
                mStartMarker.requestFocus();
                markerFocus(mStartMarker);
            }
        }
    };

    protected OnClickListener mMarkStartListener = new OnClickListener() {
        public void onClick(View sender) {
            if (mIsPlaying) {
                mStartPos = mWaveformView.millisecsToPixels(mPlayer.getCurrentPosition() + mPlayStartOffset);
                updateDisplay();
            }
        }
    };

    protected OnClickListener mMarkEndListener = new OnClickListener() {
        public void onClick(View sender) {
            if (mIsPlaying) {
                mEndPos = mWaveformView.millisecsToPixels(mPlayer.getCurrentPosition() + mPlayStartOffset);
                updateDisplay();
                handlePause();
            }
        }
    };

    protected TextWatcher mTextWatcher = new TextWatcher() {
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        public void afterTextChanged(Editable s) {
            if (mStartText.hasFocus()) {
                try {
                    mStartPos = mWaveformView.secondsToPixels(Double.parseDouble(mStartText.getText().toString()));
                    updateDisplay();
                } catch (NumberFormatException e) {
                }
            }
            if (mEndText.hasFocus()) {
                try {
                    mEndPos = mWaveformView.secondsToPixels(Double.parseDouble(mEndText.getText().toString()));
                    updateDisplay();
                } catch (NumberFormatException e) {
                }
            }
        }
    };

    protected abstract String getFileName();

    protected abstract String getImagesPath();

    protected abstract Drawable getBackground();

    protected List<Segment> getSegments() {
        return null;
    }

    protected OnClickListener getFwdListener() {
        return mFfwdListener;
    }

    protected OnClickListener get10FwdListener() {
        return m10FfwdListener;
    }

    protected OnClickListener getRewindListener() {
        return mRewindListener;
    }

    protected OnClickListener get10RewindListener() {
        return m10RewindListener;
    }

    protected int getStep(Boolean isTen) {
        int maxSeconds = (int) mWaveformView.pixelsToSeconds(mWaveformView.maxPos());
        if (maxSeconds / 3600 > 0) {
            return 600;
        } else if (maxSeconds / 1800 > 0) {
            return 300;
        } else if (maxSeconds / 300 > 0) {
            return 60;
        }
        return (isTen) ? 10 : 5;
    }

    private String convertId(int id) {
        StringBuilder idStr = new StringBuilder();
        for (int i = 0; i < 4 - Integer.toString(id / 5).length(); i++) {
            idStr.append("0");
        }
        idStr.append(Integer.toString(id / 5));
        return idStr.toString();
    }

    private void showFinalAlert(Exception e, CharSequence message) {
        CharSequence title;
        if (e != null) {
            Log.e("Ringdroid", "Error: " + message);
            e.printStackTrace();
            title = "faile";
        } else {
            Log.v("Ringdroid", "Success: " + message);
            title = "success";
        }

        new AlertDialog.Builder(getContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(
                        "ok",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                                int whichButton) {
                                dialog.dismiss();
                            }
                        })
                .setCancelable(false)
                .show();
    }

    private void showFinalAlert(Exception e, int messageResourceId) {
        showFinalAlert(e, getResources().getText(messageResourceId));
    }
}