package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2022 by Marcel Bokhorst (M66B)
*/

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.QuoteSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.Toast;

import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;
import androidx.preference.PreferenceManager;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.List;
import java.util.concurrent.ExecutorService;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

public class EditTextCompose extends FixedEditText {
    private boolean raw = false;
    private ISelection selectionListener = null;
    private IInputContentListener inputContentListener = null;

    private Boolean canUndo = null;
    private Boolean canRedo = null;
    private List<EntityAnswer> snippets;

    private int colorPrimary;
    private int colorBlockquote;
    private int quoteGap;
    private int quoteStripe;

    private static final ExecutorService executor =
            Helper.getBackgroundExecutor(1, "paste");

    public EditTextCompose(Context context) {
        super(context);
        init(context);
    }

    public EditTextCompose(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public EditTextCompose(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    void init(Context context) {
        Helper.setKeyboardIncognitoMode(this, context);

        colorPrimary = Helper.resolveColor(context, R.attr.colorPrimary);
        colorBlockquote = Helper.resolveColor(context, R.attr.colorBlockquote, colorPrimary);
        quoteGap = context.getResources().getDimensionPixelSize(R.dimen.quote_gap_size);
        quoteStripe = context.getResources().getDimensionPixelSize(R.dimen.quote_stripe_width);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean undo_manager = prefs.getBoolean("undo_manager", false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setCustomInsertionActionModeCallback(new ActionMode.Callback() {
                @Override
                public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                    try {
                        int order = 1000;
                        if (undo_manager && can(android.R.id.undo))
                            menu.add(Menu.CATEGORY_SECONDARY, R.string.title_undo, order++, getTitle(R.string.title_undo));
                        if (undo_manager && can(android.R.id.redo))
                            menu.add(Menu.CATEGORY_SECONDARY, R.string.title_redo, order++, getTitle(R.string.title_redo));
                        menu.add(Menu.CATEGORY_SECONDARY, R.string.title_insert_line, order++, context.getString(R.string.title_insert_line));
                        if (BuildConfig.DEBUG)
                            menu.add(Menu.CATEGORY_SECONDARY, R.string.title_insert_arrow, order++, context.getString(R.string.title_insert_arrow));
                        if (snippets != null)
                            for (EntityAnswer snippet : snippets) {
                                menu.add(Menu.CATEGORY_SECONDARY, order, order, snippet.name).
                                        setIntent(new Intent().putExtra("id", snippet.id));
                                order++;
                            }
                    } catch (Throwable ex) {
                        Log.e(ex);
                    }
                    return true;
                }

                private CharSequence getTitle(int resid) {
                    SpannableStringBuilder ssb = new SpannableStringBuilderEx(context.getString(resid));
                    ssb.setSpan(new StyleSpan(Typeface.ITALIC), 0, ssb.length(), 0);
                    return ssb;
                }

                @Override
                public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                    return false;
                }

                @Override
                public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                    if (item.getGroupId() == Menu.CATEGORY_SECONDARY) {
                        int id = item.getItemId();
                        if (id == R.string.title_undo)
                            return EditTextCompose.super.onTextContextMenuItem(android.R.id.undo);
                        else if (id == R.string.title_redo)
                            return EditTextCompose.super.onTextContextMenuItem(android.R.id.redo);
                        else if (id == R.string.title_insert_line)
                            return insertLine();
                        else if (id == R.string.title_insert_arrow)
                            return insertArrow();
                        else {
                            Intent intent = item.getIntent();
                            if (intent == null)
                                return false;
                            return insertSnippet(intent.getLongExtra("id", -1L));
                        }
                    }
                    return false;
                }

                @Override
                public void onDestroyActionMode(ActionMode mode) {
                    // Do nothing
                }

                private boolean insertLine() {
                    try {
                        int start = getSelectionStart();
                        if (start < 0)
                            return false;

                        Editable edit = getText();
                        if (edit == null)
                            return false;

                        if (start == 0 || edit.charAt(start - 1) != '\n')
                            edit.insert(start++, "\n");
                        if (start == edit.length() || edit.charAt(start) != '\n')
                            edit.insert(start, "\n");

                        edit.insert(start, "\uFFFC"); // Object replacement character

                        int colorSeparator = Helper.resolveColor(getContext(), R.attr.colorSeparator);
                        float stroke = context.getResources().getDisplayMetrics().density;
                        edit.setSpan(
                                new LineSpan(colorSeparator, stroke, 0f),
                                start, start + 1,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                        setSelection(start + 2);

                        return true;
                    } catch (Throwable ex) {
                        Log.e(ex);
                        ToastEx.makeText(context, Log.formatThrowable(ex), Toast.LENGTH_LONG).show();
                        return false;
                    }
                }

                private boolean insertArrow() {
                    int start = getSelectionStart();
                    if (start < 0)
                        return false;

                    Editable edit = getText();
                    if (edit == null)
                        return false;

                    edit.insert(start, " \u27f6 ");
                    return true;
                }

                private boolean insertSnippet(long id) {
                    if (snippets == null)
                        return false;

                    InternetAddress[] to = null;
                    try {
                        View root = getRootView();
                        EditText etTo = (root == null ? null : root.findViewById(R.id.etTo));
                        if (etTo != null)
                            to = MessageHelper.parseAddresses(getContext(), etTo.getText().toString());
                    } catch (AddressException ignored) {
                    }

                    for (EntityAnswer snippet : snippets)
                        if (snippet.id.equals(id)) {
                            String html = snippet.getHtml(to);

                            executor.submit(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        SpannableStringBuilder ssb = getSpanned(context, html);
                                        int len = ssb.length();
                                        if (len > 0 && ssb.charAt(len - 1) == '\n')
                                            ssb.replace(len - 1, len, " ");

                                        EditTextCompose.this.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    int start = getSelectionStart();
                                                    if (start < 0)
                                                        start = 0;

                                                    Editable edit = getText();

                                                    if (start > 0) {
                                                        char kar = edit.charAt(start - 1);
                                                        if (!(kar == '\n' || kar == ' '))
                                                            edit.insert(start++, " ");
                                                    }

                                                    edit.insert(start, ssb);

                                                    setSelection(start + ssb.length());
                                                } catch (Throwable ex) {
                                                    Log.e(ex);
                                                }
                                            }
                                        });
                                    } catch (Throwable ex) {
                                        Log.e(ex);
                                    }
                                }
                            });

                            return true;
                        }

                    return false;
                }
            });

            DB db = DB.getInstance(context);
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        snippets = db.answer().getSnippets();
                    } catch (Throwable ex) {
                        Log.e(ex);
                    }
                }
            });
        }
    }

    private boolean can(int what) {
        canUndo = null;
        canRedo = null;

        try {
            int meta = KeyEvent.META_CTRL_ON;
            if (what == android.R.id.redo)
                meta = meta | KeyEvent.META_SHIFT_ON;
            KeyEvent ke = new KeyEvent(0, 0, 0, 0, 0, meta);
            onKeyShortcut(KeyEvent.KEYCODE_Z, ke);
        } catch (Throwable ex) {
            Log.e(ex);
        }

        return Boolean.TRUE.equals(what == android.R.id.redo ? canRedo : canUndo);
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        return new SavedState(superState, this.raw);
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        SavedState savedState = (SavedState) state;
        super.onRestoreInstanceState(savedState.getSuperState());
        setRaw(savedState.getRaw());
    }

    @Override
    protected void onAttachedToWindow() {
        // Spellchecker workaround
        boolean enabled = isEnabled();
        super.setEnabled(true);
        super.onAttachedToWindow();
        super.setEnabled(enabled);
    }

    public void setRaw(boolean raw) {
        this.raw = raw;
    }

    public boolean isRaw() {
        return raw;
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (selectionListener != null)
            selectionListener.onSelected(hasSelection());
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        try {
            if (id == android.R.id.copy) {
                int start = getSelectionStart();
                int end = getSelectionEnd();
                if (start > end) {
                    int s = start;
                    start = end;
                    end = s;
                }

                Context context = getContext();
                ClipboardManager cbm = Helper.getSystemService(context, ClipboardManager.class);
                if (start != end && cbm != null) {
                    CharSequence selected = getEditableText().subSequence(start, end);
                    if (selected instanceof Spanned) {
                        String html = HtmlHelper.toHtml((Spanned) selected, context);
                        cbm.setPrimaryClip(ClipData.newHtmlText(context.getString(R.string.app_name), selected, html));
                        setSelection(end);
                        return true;
                    }
                }
            } else if (id == android.R.id.paste) {
                final Context context = getContext();

                ClipboardManager cbm = Helper.getSystemService(context, ClipboardManager.class);
                if (cbm == null || !cbm.hasPrimaryClip())
                    return false;

                ClipData.Item item = cbm.getPrimaryClip().getItemAt(0);

                final String html;
                String h = (raw ? null : item.getHtmlText());
                if (h == null) {
                    CharSequence text = item.getText();
                    if (text == null)
                        return false;
                    if (raw)
                        html = text.toString();
                    else
                        html = "<div>" + HtmlHelper.formatPlainText(text.toString(), false) + "</div>";
                } else
                    html = h;

                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            SpannableStringBuilder ssb = (raw)
                                    ? new SpannableStringBuilderEx(html)
                                    : getSpanned(context, html);

                            EditTextCompose.this.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        int start = getSelectionStart();
                                        int end = getSelectionEnd();

                                        if (start < 0)
                                            start = 0;
                                        if (end < 0)
                                            end = 0;

                                        if (start > end) {
                                            int tmp = start;
                                            start = end;
                                            end = tmp;
                                        }

                                        if (start == end)
                                            getText().insert(start, ssb);
                                        else
                                            getText().replace(start, end, ssb);
                                    } catch (Throwable ex) {
                                        Log.e(ex);
                                        /*
                                            java.lang.RuntimeException: PARAGRAPH span must start at paragraph boundary
                                                    at android.text.SpannableStringBuilder.setSpan(SpannableStringBuilder.java:619)
                                                    at android.text.SpannableStringBuilder.change(SpannableStringBuilder.java:391)
                                                    at android.text.SpannableStringBuilder.replace(SpannableStringBuilder.java:496)
                                                    at android.text.SpannableStringBuilder.replace(SpannableStringBuilder.java:454)
                                                    at android.text.SpannableStringBuilder.replace(SpannableStringBuilder.java:33)
                                                    at android.widget.TextView.paste(TextView.java:8891)
                                                    at android.widget.TextView.onTextContextMenuItem(TextView.java:8706)
                                         */
                                    }
                                }
                            });
                        } catch (Throwable ex) {
                            Log.e(ex);
                        }
                    }
                });

                return true;
            } else if (id == android.R.id.undo) {
                canUndo = true;
                return true;
            } else if (id == android.R.id.redo) {
                canRedo = true;
                return true;
            }

            return super.onTextContextMenuItem(id);
        } catch (Throwable ex) {
            Log.e(ex);
            return false;
        }
    }

    private SpannableStringBuilder getSpanned(Context context, String html) {
        Document document = HtmlHelper.sanitizeCompose(context, html, false);
        Spanned paste = HtmlHelper.fromDocument(context, document, new HtmlHelper.ImageGetterEx() {
            @Override
            public Drawable getDrawable(Element element) {
                return ImageHelper.decodeImage(context,
                        -1, element, true, 0, 1.0f, EditTextCompose.this);
            }
        }, null);

        SpannableStringBuilder ssb = new SpannableStringBuilderEx(paste);
        QuoteSpan[] spans = ssb.getSpans(0, ssb.length(), QuoteSpan.class);
        for (QuoteSpan span : spans) {
            QuoteSpan q;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
                q = new QuoteSpan(colorBlockquote);
            else
                q = new QuoteSpan(colorBlockquote, quoteStripe, quoteGap);
            ssb.setSpan(q,
                    ssb.getSpanStart(span),
                    ssb.getSpanEnd(span),
                    ssb.getSpanFlags(span));
            ssb.removeSpan(span);
        }

        return ssb;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
        //https://developer.android.com/guide/topics/text/image-keyboard
        InputConnection ic = super.onCreateInputConnection(editorInfo);
        if (ic == null)
            return null;

        EditorInfoCompat.setContentMimeTypes(editorInfo, new String[]{"image/*"});

        return InputConnectionCompat.createWrapper(ic, editorInfo, new InputConnectionCompat.OnCommitContentListener() {
            @Override
            public boolean onCommitContent(InputContentInfoCompat info, int flags, Bundle opts) {
                Log.i("Uri=" + info.getContentUri());
                try {
                    if (inputContentListener == null)
                        throw new IllegalArgumentException("InputContent listener not set");

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
                            (flags & InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0)
                        info.requestPermission();

                    inputContentListener.onInputContent(info.getContentUri());
                    return true;
                } catch (Throwable ex) {
                    Log.w(ex);
                    return false;
                }
            }
        });
    }

    void setInputContentListener(IInputContentListener listener) {
        this.inputContentListener = listener;
    }

    interface IInputContentListener {
        void onInputContent(Uri uri);
    }

    void setSelectionListener(ISelection listener) {
        this.selectionListener = listener;
    }

    interface ISelection {
        void onSelected(boolean selection);
    }

    static class SavedState extends View.BaseSavedState {
        private boolean raw;

        private SavedState(Parcelable superState, boolean raw) {
            super(superState);
            this.raw = raw;
        }

        private SavedState(Parcel in) {
            super(in);
            raw = (in.readInt() != 0);
        }

        public boolean getRaw() {
            return this.raw;
        }

        @Override
        public void writeToParcel(Parcel destination, int flags) {
            super.writeToParcel(destination, flags);
            destination.writeInt(raw ? 1 : 0);
        }

        public static final Parcelable.Creator<SavedState> CREATOR = new Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }
}