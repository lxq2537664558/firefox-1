/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.home;

import org.mozilla.gecko.animation.PropertyAnimator;
import org.mozilla.gecko.animation.PropertyAnimator.Property;
import org.mozilla.gecko.animation.PropertyAnimator.PropertyAnimationListener;
import org.mozilla.gecko.animation.ViewHelper;
import org.mozilla.gecko.GeckoAppShell;
import org.mozilla.gecko.GeckoEvent;
import org.mozilla.gecko.R;
import org.mozilla.gecko.gfx.BitmapUtils;
import org.mozilla.gecko.util.GeckoEventListener;
import org.mozilla.gecko.util.ThreadUtils;

import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class HomeBanner extends LinearLayout
                        implements GeckoEventListener {
    private static final String LOGTAG = "GeckoHomeBanner";

    // Used for tracking scroll length
    private float mTouchY = -1;

    // Used to detect for upwards scroll to push banner all the way up
    private boolean mSnapBannerToTop;

    // Tracks whether or not the banner should be shown on the current panel.
    private boolean mActive = false;

    // The user is currently swiping between HomePager pages
    private boolean mScrollingPages = false;

    // Tracks whether the user swiped the banner down, preventing us from autoshowing when the user
    // switches back to the default page.
    private boolean mUserSwipedDown = false;

    private final TextView mTextView;
    private final ImageView mIconView;

    // The height of the banner view.
    private final float mHeight;

    // Listener that gets called when the banner is dismissed from the close button.
    private OnDismissListener mOnDismissListener;

    public interface OnDismissListener {
        public void onDismiss();
    }

    public HomeBanner(Context context) {
        this(context, null);
    }

    public HomeBanner(Context context, AttributeSet attrs) {
        super(context, attrs);

        LayoutInflater.from(context).inflate(R.layout.home_banner, this);

        mTextView = (TextView) findViewById(R.id.text);
        mIconView = (ImageView) findViewById(R.id.icon);

        mHeight = getResources().getDimensionPixelSize(R.dimen.home_banner_height);

        // Disable the banner until a message is set.
        setEnabled(false);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        // Tapping on the close button will ensure that the banner is never
        // showed again on this session.
        final ImageButton closeButton = (ImageButton) findViewById(R.id.close);

        // The drawable should have 50% opacity.
        closeButton.getDrawable().setAlpha(127);

        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                HomeBanner.this.dismiss();

                // Send the current message id back to JS.
                GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("HomeBanner:Dismiss", (String) getTag()));
            }
        });

        setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HomeBanner.this.dismiss();

                // Send the current message id back to JS.
                GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("HomeBanner:Click", (String) getTag()));
            }
        });

        GeckoAppShell.getEventDispatcher().registerEventListener("HomeBanner:Data", this);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        GeckoAppShell.getEventDispatcher().unregisterEventListener("HomeBanner:Data", this);
    }

    @Override
    public void setVisibility(int visibility) {
        // On pre-Honeycomb devices, setting the visibility to GONE won't actually
        // hide the view unless we clear animations first.
        if (Build.VERSION.SDK_INT < 11 && visibility == View.GONE) {
            clearAnimation();
        }

        super.setVisibility(visibility);
    }

    public void setScrollingPages(boolean scrollingPages) {
        mScrollingPages = scrollingPages;
    }

    public void setOnDismissListener(OnDismissListener listener) {
        mOnDismissListener = listener;
    }

    /**
     * Hides and disables the banner.
     */
    private void dismiss() {
        setVisibility(View.GONE);
        setEnabled(false);

        if (mOnDismissListener != null) {
            mOnDismissListener.onDismiss();
        }
    }

    /**
     * Sends a message to gecko to request a new banner message. UI is updated in handleMessage.
     */
    public void update() {
        GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("HomeBanner:Get", null));
    }

    @Override
    public void handleMessage(String event, JSONObject message) {
        final String id = message.optString("id");
        final String text = message.optString("text");
        final String iconURI = message.optString("iconURI");

        // Don't update the banner if the message doesn't have valid id and text.
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(text)) {
            return;
        }

        // Update the banner message on the UI thread.
        ThreadUtils.postToUiThread(new Runnable() {
            @Override
            public void run() {
                // Store the current message id to pass back to JS in the view's OnClickListener.
                setTag(id);
                mTextView.setText(Html.fromHtml(text));

                BitmapUtils.getDrawable(getContext(), iconURI, new BitmapUtils.BitmapLoader() {
                    @Override
                    public void onBitmapFound(final Drawable d) {
                        // Hide the image view if we don't have an icon to show.
                        if (d == null) {
                            mIconView.setVisibility(View.GONE);
                        } else {
                            mIconView.setImageDrawable(d);
                        }
                    }
                });

                GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("HomeBanner:Shown", id));

                // Enable the banner after a message is set.
                setEnabled(true);

                // Animate the banner if it is currently active.
                if (mActive) {
                    animateUp();
                }
            }
        });
    }

    public void setActive(boolean active) {
        // No need to animate if not changing
        if (mActive == active) {
            return;
        }

        mActive = active;

        // Don't animate if the banner isn't enabled.
        if (!isEnabled()) {
            return;
        }

        if (active) {
            animateUp();
        } else {
            animateDown();
        }
    }

    private void ensureVisible() {
        // The banner visibility is set to GONE after it is animated off screen,
        // so we need to make it visible again.
        if (getVisibility() == View.GONE) {
            // Translate the banner off screen before setting it to VISIBLE.
            ViewHelper.setTranslationY(this, mHeight);
            setVisibility(View.VISIBLE);
        }
    }

    private void animateUp() {
        // Don't try to animate if the user swiped the banner down previously to hide it.
        if (mUserSwipedDown) {
            return;
        }

        ensureVisible();

        final PropertyAnimator animator = new PropertyAnimator(100);
        animator.attach(this, Property.TRANSLATION_Y, 0);
        animator.start();
    }

    private void animateDown() {
        if (ViewHelper.getTranslationY(this) == mHeight) {
            // Hide the banner to avoid intercepting clicks on pre-honeycomb devices.
            setVisibility(View.GONE);
            return;
        }

        final PropertyAnimator animator = new PropertyAnimator(100);
        animator.attach(this, Property.TRANSLATION_Y, mHeight);
        animator.addPropertyAnimationListener(new PropertyAnimator.PropertyAnimationListener() {
            @Override
            public void onPropertyAnimationStart() {
            }

            @Override
            public void onPropertyAnimationEnd() {
                // Hide the banner to avoid intercepting clicks on pre-honeycomb devices.
                setVisibility(View.GONE);
            }
        });
        animator.start();
    }

    public void handleHomeTouch(MotionEvent event) {
        if (!mActive || !isEnabled() || mScrollingPages) {
            return;
        }

        ensureVisible();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // Track the beginning of the touch
                mTouchY = event.getRawY();
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                final float curY = event.getRawY();
                final float delta = mTouchY - curY;
                mSnapBannerToTop = delta <= 0.0f;

                float newTranslationY = ViewHelper.getTranslationY(this) + delta;

                // Clamp the values to be between 0 and height.
                if (newTranslationY < 0.0f) {
                    newTranslationY = 0.0f;
                } else if (newTranslationY > mHeight) {
                    newTranslationY = mHeight;
                }

                // Don't change this value if it wasn't a significant movement
                if (delta >= 10 || delta <= -10) {
                    mUserSwipedDown = (newTranslationY == mHeight);
                }

                ViewHelper.setTranslationY(this, newTranslationY);
                mTouchY = curY;
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mTouchY = -1;
                if (mSnapBannerToTop) {
                    animateUp();
                } else {
                    animateDown();
                    mUserSwipedDown = true;
                }
                break;
            }
        }
    }
}
