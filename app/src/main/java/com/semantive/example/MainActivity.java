package com.semantive.example;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import com.semantive.waveformandroid.waveform.Segment;
import com.semantive.waveformandroid.waveform.WaveformFragment;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.container, new CustomWaveformFragment())
                    .commit();
        }
    }

    public static class CustomWaveformFragment extends WaveformFragment {

        /**
         * Provide path to your audio file.
         *
         * @return
         */

        @Override
        protected String getFileName() {
            return "/sdcard/FUTURE LAB/1546058028.m4a";
        }


        /**
         * Provide path to your image file.
         *
         * @return
         */

        @Override
        protected String getImagesPath() {
            return "/sdcard/FUTURE LAB/2287/";
        }

        @Override
        protected Drawable getBackground() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return getContext().getDrawable(R.drawable.ninja);
            }
            return null;
        }

        /**
         * Optional - provide list of segments (start and stop values in seconds) and their corresponding colors
         *
         * @return
         */
        @Override
        protected List<Segment> getSegments() {

            return Arrays.asList(
                    new Segment(55.2, 55.8, Color.rgb(238, 23, 104)),
                    new Segment(56.2, 56.6, Color.rgb(238, 23, 104)),
                    new Segment(58.4, 59.9, Color.rgb(184, 92, 184)));
        }

        @Override
        protected synchronized void updateDisplay() {
            Log.d("MrxAudio", "time = ");
//            setScreenImage("tom_3", getActivity(), "com.semantive.example");
            super.updateDisplay();
        }


        @Override
        public void onPause() {
            Log.d("MrxKjm", "mEndPos = " + formatTime(mStartPos));
            super.onPause();
        }
    }
}
