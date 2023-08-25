/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.nearby.halfsheet.utils;

import static com.android.nearby.halfsheet.constants.Constant.TAG;

import static java.util.Objects.requireNonNull;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * Util class for launching help page in Fast Pair.
 */
public class HelpUtils {
    /**
     * Sets up the info button to launch a help page
     */
    public static void showHelpPage(Context context, String url)  {
        requireNonNull(context, "context cannot be null");
        requireNonNull(url, "url cannot be null");

        try {
            context.startActivity(createHelpPageIntent(url));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Failed to find activity for url " + url, e);
        }
    }

    /**
     * Creates the intent for help page
     */
    private static Intent createHelpPageIntent(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
