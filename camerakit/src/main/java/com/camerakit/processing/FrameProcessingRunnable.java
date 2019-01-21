package com.camerakit.processing;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.Frame;

import java.nio.ByteBuffer;

class FrameProcessingRunnable implements Runnable {
    private Detector<?> mDetector;
    private long mStartTimeMillis = SystemClock.elapsedRealtime();

    // This lock guards all of the member variables below.
    private final Object mLock = new Object();
    private boolean mActive = true;

    // These pending variables hold the state associated with the new frame awaiting processing.
    private long mPendingTimeMillis;
    private int mPendingFrameId = 0;
    private byte[] mPendingFrameData;

    FrameProcessingRunnable(Detector<?> detector) {
        mDetector = detector;
    }

    /**
     * Releases the underlying receiver.  This is only safe to do after the associated thread
     * has completed, which is managed in camera source's release method above.
     */
    @SuppressLint("Assert")
    void release() {
        assert (mProcessingThread.getState() == Thread.State.TERMINATED);
        mDetector.release();
        mDetector = null;
    }

    /**
     * Marks the runnable as active/not active.  Signals any blocked threads to continue.
     */
    void setActive(boolean active) {
        synchronized (mLock) {
            mActive = active;
            mLock.notifyAll();
        }
    }

    /**
     * Sets the frame data received from the camera.
     */
    void setNextFrame(byte[] data) {
        synchronized (mLock) {
            if (mPendingFrameData != null) {
                mPendingFrameData = null;
            }

            // Timestamp and frame ID are maintained here, which will give downstream code some
            // idea of the timing of frames received and when frames were dropped along the way.
            mPendingTimeMillis = SystemClock.elapsedRealtime() - mStartTimeMillis;
            mPendingFrameId++;
            mPendingFrameData = data;

            // Notify the processor thread if it is waiting on the next frame (see below).
            mLock.notifyAll();
        }
    }

    /**
     * As long as the processing thread is active, this executes detection on frames
     * continuously.  The next pending frame is either immediately available or hasn't been
     * received yet.  Once it is available, we transfer the frame info to local variables and
     * run detection on that frame.  It immediately loops back for the next frame without
     * pausing.
     * <p/>
     * If detection takes longer than the time in between new frames from the camera, this will
     * mean that this loop will run without ever waiting on a frame, avoiding any context
     * switching or frame acquisition time latency.
     * <p/>
     * If you find that this is using more CPU than you'd like, you should probably decrease the
     * FPS setting above to allow for some idle time in between frames.
     */
    @Override
    public void run() {
        Frame outputFrame;

        while (true) {
            synchronized (mLock) {
                while (mActive && (mPendingFrameData == null)) {
                    try {
                        // Wait for the next frame to be received from the camera, since we
                        // don't have it yet.
                        mLock.wait();
                    } catch (InterruptedException e) {
                        Log.d("FrameProcessingRunnable", "Frame processing loop terminated.", e);
                        return;
                    }
                }

                if (!mActive) {
                    // Exit the loop once this camera source is stopped or released.  We check
                    // this here, immediately after the wait() above, to handle the case where
                    // setActive(false) had been called, triggering the termination of this
                    // loop.
                    return;
                }

                outputFrame = new Frame.Builder()
                        .setImageData(ByteBuffer.wrap(quarterNV21(mPendingFrameData, mPreviewSize.getWidth(), mPreviewSize.getHeight())), mPreviewSize.getWidth()/4, mPreviewSize.getHeight()/4, ImageFormat.NV21)
                        .setId(mPendingFrameId)
                        .setTimestampMillis(mPendingTimeMillis)
                        .setRotation(getDetectorOrientation(mSensorOrientation))
                        .build();

                // We need to clear mPendingFrameData to ensure that this buffer isn't
                // recycled back to the camera before we are done using that data.
                mPendingFrameData = null;
            }

            // The code below needs to run outside of synchronization, because this will allow
            // the camera to add pending frame(s) while we are running detection on the current
            // frame.

            try {
                mDetector.receiveFrame(outputFrame);
            } catch (Throwable t) {
                Log.e("", "Exception thrown from receiver.", t);
            }
        }
    }

    private byte[] quarterNV21(byte[] data, int iWidth, int iHeight) {
        // Reduce to quarter size the NV21 frame
        byte[] yuv = new byte[iWidth/4 * iHeight/4 * 3 / 2];
        // halve yuma
        int i = 0;
        for (int y = 0; y < iHeight; y += 4) {
            for (int x = 0; x < iWidth; x += 4) {
                yuv[i] = data[y * iWidth + x];
                i++;
            }
        }
        // halve U and V color components
        /*
        for (int y = 0; y < iHeight / 2; y+=4) {
            for (int x = 0; x < iWidth; x += 8) {
                yuv[i] = data[(iWidth * iHeight) + (y * iWidth) + x];
                i++;
                yuv[i] = data[(iWidth * iHeight) + (y * iWidth) + (x + 1)];
                i++;
            }
        }*/
        return yuv;
    }
}