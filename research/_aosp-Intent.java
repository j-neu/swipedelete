/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content;

import static android.app.sdksandbox.SdkSandboxManager.ACTION_START_SANDBOXED_ACTIVITY;
import static android.content.ContentProvider.maybeAddUserId;
import static android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE;
import static android.security.Flags.FLAG_FRP_ENFORCEMENT;
import static android.security.Flags.FLAG_PREVENT_INTENT_REDIRECT;
import static android.security.Flags.preventIntentRedirect;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.annotation.AnyRes;
import android.annotation.BroadcastBehavior;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Activity;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.StatusBarManager;
import android.bluetooth.BluetoothDevice;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.Build;
import android.os.Bundle;
import android.os.BundleMerger;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.IncidentManager;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.ShellCommand;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.ContactsContract.QuickContact;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.service.chooser.AdditionalContentContract;
import android.service.chooser.ChooserAction;
import android.service.chooser.ChooserResult;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.XmlUtils;
import com.android.modules.expresslog.Counter;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Consumer;

/**
 * An intent is an abstract description of an operation to be performed.  It
 * can be used with {@link Context#startActivity(Intent) startActivity} to
 * launch an {@link android.app.Activity},
 * {@link android.content.Context#sendBroadcast(Intent) broadcastIntent} to
 * send it to any interested {@link BroadcastReceiver BroadcastReceiver} components,
 * and {@link android.content.Context#startService} or
 * {@link android.content.Context#bindService} to communicate with a
 * background {@link android.app.Service}.
 *
 * <p>An Intent provides a facility for performing late runtime binding between the code in
 * different applications. Its most significant use is in the launching of activities, where it
 * can be thought of as the glue between activities. It is basically a passive data structure
 * holding an abstract description of an action to be performed.</p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For information about how to create and resolve intents, read the
 * <a href="{@docRoot}guide/topics/intents/intents-filters.html">Intents and Intent Filters</a>
 * developer guide.</p>
 * </div>
 *
 * <a name="IntentStructure"></a>
 * <h3>Intent Structure</h3>
 * <p>The primary pieces of information in an intent are:</p>
 *
 * <ul>
 *   <li> <p><b>action</b> -- The general action to be performed, such as
 *     {@link #ACTION_VIEW}, {@link #ACTION_EDIT}, {@link #ACTION_MAIN},
 *     etc.</p>
 *   </li>
 *   <li> <p><b>data</b> -- The data to operate on, such as a person record
 *     in the contacts database, expressed as a {@link android.net.Uri}.</p>
 *   </li>
 * </ul>
 *
 *
 * <p>Some examples of action/data pairs are:</p>
 *
 * <ul>
 *   <li> <p><b>{@link #ACTION_VIEW} <i>content://contacts/people/1</i></b> -- Display
 *     information about the person whose identifier is "1".</p>
 *   </li>
 *   <li> <p><b>{@link #ACTION_DIAL} <i>content://contacts/people/1</i></b> -- Display
 *     the phone dialer with the person filled in.</p>
 *   </li>
 *   <li> <p><b>{@link #ACTION_VIEW} <i>tel:123</i></b> -- Display
 *     the phone dialer with the given number filled in.  Note how the
 *     VIEW action does what is considered the most reasonable thing for
 *     a particular URI.</p>
 *   </li>
 *   <li> <p><b>{@link #ACTION_DIAL} <i>tel:123</i></b> -- Display
 *     the phone dialer with the given number filled in.</p>
 *   </li>
 *   <li> <p><b>{@link #ACTION_EDIT} <i>content://contacts/people/1</i></b> -- Edit
 *     information about the person whose identifier is "1".</p>
 *   </li>
 *   <li> <p><b>{@link #ACTION_VIEW} <i>content://contacts/people/</i></b> -- Display
 *     a list of people, which the user can browse through.  This example is a
 *     typical top-level entry into the Contacts application, showing you the
 *     list of people. Selecting a particular person to view would result in a
 *     new intent { <b>{@link #ACTION_VIEW} <i>content://contacts/people/N</i></b> }
 *     being used to start an activity to display that person.</p>
 *   </li>
 * </ul>
 *
 * <p>In addition to these primary attributes, there are a number of secondary
 * attributes that you can also include with an intent:</p>
 *
 * <ul>
 *     <li> <p><b>category</b> -- Gives additional information about the action
 *         to execute.  For example, {@link #CATEGORY_LAUNCHER} means it should
 *         appear in the Launcher as a top-level application, while
 *         {@link #CATEGORY_ALTERNATIVE} means it should be included in a list
 *         of alternative actions the user can perform on a piece of data.</p>
 *     <li> <p><b>type</b> -- Specifies an explicit type (a MIME type) of the
 *         intent data.  Normally the type is inferred from the data itself.
 *         By setting this attribute, you disable that evaluation and force
 *         an explicit type.</p>
 *     <li> <p><b>component</b> -- Specifies an explicit name of a component
 *         class to use for the intent.  Normally this is determined by looking
 *         at the other information in the intent (the action, data/type, and
 *         categories) and matching that with a component that can handle it.
 *         If this attribute is set then none of the evaluation is performed,
 *         and this component is used exactly as is.  By specifying this attribute,
 *         all of the other Intent attributes become optional.</p>
 *     <li> <p><b>extras</b> -- This is a {@link Bundle} of any additional information.
 *         This can be used to provide extended information to the component.
 *         For example, if we have a action to send an e-mail message, we could
 *         also include extra pieces of data here to supply a subject, body,
 *         etc.</p>
 * </ul>
 *
 * <p>Here are some examples of other operations you can specify as intents
 * using these additional parameters:</p>
 *
 * <ul>
 *   <li> <p><b>{@link #ACTION_MAIN} with category {@link #CATEGORY_HOME}</b> --
 *     Launch the home screen.</p>
 *   </li>
 *   <li> <p><b>{@link #ACTION_GET_CONTENT} with MIME type
 *     <i>{@link android.provider.Contacts.Phones#CONTENT_URI
 *     vnd.android.cursor.item/phone}</i></b>
 *     -- Display the list of people's phone numbers, allowing the user to
 *     browse through them and pick one and return it to the parent activity.</p>
 *   </li>
 *   <li> <p><b>{@link #ACTION_GET_CONTENT} with MIME type
 *     <i>*{@literal /}*</i> and category {@link #CATEGORY_OPENABLE}</b>
 *     -- Display all pickers for data that can be opened with
 *     {@link ContentResolver#openInputStream(Uri) ContentResolver.openInputStream()},
 *     allowing the user to pick one of them and then some data inside of it
 *     and returning the resulting URI to the caller.  This can be used,
 *     for example, in an e-mail application to allow the user to pick some
 *     data to include as an attachment.</p>
 *   </li>
 * </ul>
 *
 * <p>There are a variety of standard Intent action and category constants
 * defined in the Intent class, but applications can also define their own.
 * These strings use Java-style scoping, to ensure they are unique -- for
 * example, the standard {@link #ACTION_VIEW} is called
 * "android.intent.action.VIEW".</p>
 *
 * <p>Put together, the set of actions, data types, categories, and extra data
 * defines a language for the system allowing for the expression of phrases
 * such as "call john smith's cell".  As applications are added to the system,
 * they can extend this language by adding new actions, types, and categories, or
 * they can modify the behavior of existing phrases by supplying their own
 * activities that handle them.</p>
 *
 * <a name="IntentResolution"></a>
 * <h3>Intent Resolution</h3>
 *
 * <p>There are two primary forms of intents you will use.
 *
 * <ul>
 *     <li> <p><b>Explicit Intents</b> have specified a component (via
 *     {@link #setComponent} or {@link #setClass}), which provides the exact
 *     class to be run.  Often these will not include any other information,
 *     simply being a way for an application to launch various internal
 *     activities it has as the user interacts with the application.
 *
 *     <li> <p><b>Implicit Intents</b> have not specified a component;
 *     instead, they must include enough information for the system to
 *     determine which of the available components is best to run for that
 *     intent.
 * </ul>
 *
 * <p>When using implicit intents, given such an arbitrary intent we need to
 * know what to do with it. This is handled by the process of <em>Intent
 * resolution</em>, which maps an Intent to an {@link android.app.Activity},
 * {@link BroadcastReceiver}, or {@link android.app.Service} (or sometimes two or
 * more activities/receivers) that can handle it.</p>
 *
 * <p>The intent resolution mechanism basically revolves around matching an
 * Intent against all of the &lt;intent-filter&gt; descriptions in the
 * installed application packages.  (Plus, in the case of broadcasts, any {@link BroadcastReceiver}
 * objects explicitly registered with {@link Context#registerReceiver}.)  More
 * details on this can be found in the documentation on the {@link
 * IntentFilter} class.</p>
 *
 * <p>There are three pieces of information in the Intent that are used for
 * resolution: the action, type, and category.  Using this information, a query
 * is done on the {@link PackageManager} for a component that can handle the
 * intent. The appropriate component is determined based on the intent
 * information supplied in the <code>AndroidManifest.xml</code> file as
 * follows:</p>
 *
 * <ul>
 *     <li> <p>The <b>action</b>, if given, must be listed by the component as
 *         one it handles.</p>
 *     <li> <p>The <b>type</b> is retrieved from the Intent's data, if not
 *         already supplied in the Intent.  Like the action, if a type is
 *         included in the intent (either explicitly or implicitly in its
 *         data), then this must be listed by the component as one it handles.</p>
 *     <li> For data that is not a <code>content:</code> URI and where no explicit
 *         type is included in the Intent, instead the <b>scheme</b> of the
 *         intent data (such as <code>http:</code> or <code>mailto:</code>) is
 *         considered. Again like the action, if we are matching a scheme it
 *         must be listed by the component as one it can handle.
 *     <li> <p>The <b>categories</b>, if supplied, must <em>all</em> be listed
 *         by the activity as categories it handles.  That is, if you include
 *         the categories {@link #CATEGORY_LAUNCHER} and
 *         {@link #CATEGORY_ALTERNATIVE}, then you will only resolve to components
 *         with an intent that lists <em>both</em> of those categories.
 *         Activities will very often need to support the
 *         {@link #CATEGORY_DEFAULT} so that they can be found by
 *         {@link Context#startActivity Context.startActivity()}.</p>
 * </ul>
 *
 * <p>For example, consider the Note Pad sample application that
 * allows a user to browse through a list of notes data and view details about
 * individual items.  Text in italics indicates places where you would replace a
 * name with one specific to your own package.</p>
 *
 * <pre> &lt;manifest xmlns:android="http://schemas.android.com/apk/res/android"
 *       package="<i>com.android.notepad</i>"&gt;
 *     &lt;application android:icon="@drawable/app_notes"
 *             android:label="@string/app_name"&gt;
 *
 *         &lt;provider class=".NotePadProvider"
 *                 android:authorities="<i>com.google.provider.NotePad</i>" /&gt;
 *
 *         &lt;activity class=".NotesList" android:label="@string/title_notes_list"&gt;
 *             &lt;intent-filter&gt;
 *                 &lt;action android:name="android.intent.action.MAIN" /&gt;
 *                 &lt;category android:name="android.intent.category.LAUNCHER" /&gt;
 *             &lt;/intent-filter&gt;
 *             &lt;intent-filter&gt;
 *                 &lt;action android:name="android.intent.action.VIEW" /&gt;
 *                 &lt;action android:name="android.intent.action.EDIT" /&gt;
 *                 &lt;action android:name="android.intent.action.PICK" /&gt;
 *                 &lt;category android:name="android.intent.category.DEFAULT" /&gt;
 *                 &lt;data android:mimeType="vnd.android.cursor.dir/<i>vnd.google.note</i>" /&gt;
 *             &lt;/intent-filter&gt;
 *             &lt;intent-filter&gt;
 *                 &lt;action android:name="android.intent.action.GET_CONTENT" /&gt;
 *                 &lt;category android:name="android.intent.category.DEFAULT" /&gt;
 *                 &lt;data android:mimeType="vnd.android.cursor.item/<i>vnd.google.note</i>" /&gt;
 *             &lt;/intent-filter&gt;
 *         &lt;/activity&gt;
 *
 *         &lt;activity class=".NoteEditor" android:label="@string/title_note"&gt;
 *             &lt;intent-filter android:label="@string/resolve_edit"&gt;
 *                 &lt;action android:name="android.intent.action.VIEW" /&gt;
 *                 &lt;action android:name="android.intent.action.EDIT" /&gt;
 *                 &lt;category android:name="android.intent.category.DEFAULT" /&gt;
 *                 &lt;data android:mimeType="vnd.android.cursor.item/<i>vnd.google.note</i>" /&gt;
 *             &lt;/intent-filter&gt;
 *
 *             &lt;intent-filter&gt;
 *                 &lt;action android:name="android.intent.action.INSERT" /&gt;
 *                 &lt;category android:name="android.intent.category.DEFAULT" /&gt;
 *                 &lt;data android:mimeType="vnd.android.cursor.dir/<i>vnd.google.note</i>" /&gt;
 *             &lt;/intent-filter&gt;
 *
 *         &lt;/activity&gt;
 *
 *         &lt;activity class=".TitleEditor" android:label="@string/title_edit_title"
 *                 android:theme="@android:style/Theme.Dialog"&gt;
 *             &lt;intent-filter android:label="@string/resolve_title"&gt;
 *                 &lt;action android:name="<i>com.android.notepad.action.EDIT_TITLE</i>" /&gt;
 *                 &lt;category android:name="android.intent.category.DEFAULT" /&gt;
 *                 &lt;category android:name="android.intent.category.ALTERNATIVE" /&gt;
 *                 &lt;category android:name="android.intent.category.SELECTED_ALTERNATIVE" /&gt;
 *                 &lt;data android:mimeType="vnd.android.cursor.item/<i>vnd.google.note</i>" /&gt;
 *             &lt;/intent-filter&gt;
 *         &lt;/activity&gt;
 *
 *     &lt;/application&gt;
 * &lt;/manifest&gt;</pre>
 *
 * <p>The first activity,
 * <code>com.android.notepad.NotesList</code>, serves as our main
 * entry into the app.  It can do three things as described by its three intent
 * templates:
 * <ol>
 * <li><pre>
 * &lt;intent-filter&gt;
 *     &lt;action android:name="{@link #ACTION_MAIN android.intent.action.MAIN}" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_LAUNCHER android.intent.category.LAUNCHER}" /&gt;
 * &lt;/intent-filter&gt;</pre>
 * <p>This provides a top-level entry into the NotePad application: the standard
 * MAIN action is a main entry point (not requiring any other information in
 * the Intent), and the LAUNCHER category says that this entry point should be
 * listed in the application launcher.</p>
 * <li><pre>
 * &lt;intent-filter&gt;
 *     &lt;action android:name="{@link #ACTION_VIEW android.intent.action.VIEW}" /&gt;
 *     &lt;action android:name="{@link #ACTION_EDIT android.intent.action.EDIT}" /&gt;
 *     &lt;action android:name="{@link #ACTION_PICK android.intent.action.PICK}" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_DEFAULT android.intent.category.DEFAULT}" /&gt;
 *     &lt;data android:mimeType="vnd.android.cursor.dir/<i>vnd.google.note</i>" /&gt;
 * &lt;/intent-filter&gt;</pre>
 * <p>This declares the things that the activity can do on a directory of
 * notes.  The type being supported is given with the &lt;type&gt; tag, where
 * <code>vnd.android.cursor.dir/vnd.google.note</code> is a URI from which
 * a Cursor of zero or more items (<code>vnd.android.cursor.dir</code>) can
 * be retrieved which holds our note pad data (<code>vnd.google.note</code>).
 * The activity allows the user to view or edit the directory of data (via
 * the VIEW and EDIT actions), or to pick a particular note and return it
 * to the caller (via the PICK action).  Note also the DEFAULT category
 * supplied here: this is <em>required</em> for the
 * {@link Context#startActivity Context.startActivity} method to resolve your
 * activity when its component name is not explicitly specified.</p>
 * <li><pre>
 * &lt;intent-filter&gt;
 *     &lt;action android:name="{@link #ACTION_GET_CONTENT android.intent.action.GET_CONTENT}" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_DEFAULT android.intent.category.DEFAULT}" /&gt;
 *     &lt;data android:mimeType="vnd.android.cursor.item/<i>vnd.google.note</i>" /&gt;
 * &lt;/intent-filter&gt;</pre>
 * <p>This filter describes the ability to return to the caller a note selected by
 * the user without needing to know where it came from.  The data type
 * <code>vnd.android.cursor.item/vnd.google.note</code> is a URI from which
 * a Cursor of exactly one (<code>vnd.android.cursor.item</code>) item can
 * be retrieved which contains our note pad data (<code>vnd.google.note</code>).
 * The GET_CONTENT action is similar to the PICK action, where the activity
 * will return to its caller a piece of data selected by the user.  Here,
 * however, the caller specifies the type of data they desire instead of
 * the type of data the user will be picking from.</p>
 * </ol>
 *
 * <p>Given these capabilities, the following intents will resolve to the
 * NotesList activity:</p>
 *
 * <ul>
 *     <li> <p><b>{ action=android.app.action.MAIN }</b> matches all of the
 *         activities that can be used as top-level entry points into an
 *         application.</p>
 *     <li> <p><b>{ action=android.app.action.MAIN,
 *         category=android.app.category.LAUNCHER }</b> is the actual intent
 *         used by the Launcher to populate its top-level list.</p>
 *     <li> <p><b>{ action=android.intent.action.VIEW
 *          data=content://com.google.provider.NotePad/notes }</b>
 *         displays a list of all the notes under
 *         "content://com.google.provider.NotePad/notes", which
 *         the user can browse through and see the details on.</p>
 *     <li> <p><b>{ action=android.app.action.PICK
 *          data=content://com.google.provider.NotePad/notes }</b>
 *         provides a list of the notes under
 *         "content://com.google.provider.NotePad/notes", from which
 *         the user can pick a note whose data URL is returned back to the caller.</p>
 *     <li> <p><b>{ action=android.app.action.GET_CONTENT
 *          type=vnd.android.cursor.item/vnd.google.note }</b>
 *         is similar to the pick action, but allows the caller to specify the
 *         kind of data they want back so that the system can find the appropriate
 *         activity to pick something of that data type.</p>
 * </ul>
 *
 * <p>The second activity,
 * <code>com.android.notepad.NoteEditor</code>, shows the user a single
 * note entry and allows them to edit it.  It can do two things as described
 * by its two intent templates:
 * <ol>
 * <li><pre>
 * &lt;intent-filter android:label="@string/resolve_edit"&gt;
 *     &lt;action android:name="{@link #ACTION_VIEW android.intent.action.VIEW}" /&gt;
 *     &lt;action android:name="{@link #ACTION_EDIT android.intent.action.EDIT}" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_DEFAULT android.intent.category.DEFAULT}" /&gt;
 *     &lt;data android:mimeType="vnd.android.cursor.item/<i>vnd.google.note</i>" /&gt;
 * &lt;/intent-filter&gt;</pre>
 * <p>The first, primary, purpose of this activity is to let the user interact
 * with a single note, as decribed by the MIME type
 * <code>vnd.android.cursor.item/vnd.google.note</code>.  The activity can
 * either VIEW a note or allow the user to EDIT it.  Again we support the
 * DEFAULT category to allow the activity to be launched without explicitly
 * specifying its component.</p>
 * <li><pre>
 * &lt;intent-filter&gt;
 *     &lt;action android:name="{@link #ACTION_INSERT android.intent.action.INSERT}" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_DEFAULT android.intent.category.DEFAULT}" /&gt;
 *     &lt;data android:mimeType="vnd.android.cursor.dir/<i>vnd.google.note</i>" /&gt;
 * &lt;/intent-filter&gt;</pre>
 * <p>The secondary use of this activity is to insert a new note entry into
 * an existing directory of notes.  This is used when the user creates a new
 * note: the INSERT action is executed on the directory of notes, causing
 * this activity to run and have the user create the new note data which
 * it then adds to the content provider.</p>
 * </ol>
 *
 * <p>Given these capabilities, the following intents will resolve to the
 * NoteEditor activity:</p>
 *
 * <ul>
 *     <li> <p><b>{ action=android.intent.action.VIEW
 *          data=content://com.google.provider.NotePad/notes/<var>{ID}</var> }</b>
 *         shows the user the content of note <var>{ID}</var>.</p>
 *     <li> <p><b>{ action=android.app.action.EDIT
 *          data=content://com.google.provider.NotePad/notes/<var>{ID}</var> }</b>
 *         allows the user to edit the content of note <var>{ID}</var>.</p>
 *     <li> <p><b>{ action=android.app.action.INSERT
 *          data=content://com.google.provider.NotePad/notes }</b>
 *         creates a new, empty note in the notes list at
 *         "content://com.google.provider.NotePad/notes"
 *         and allows the user to edit it.  If they keep their changes, the URI
 *         of the newly created note is returned to the caller.</p>
 * </ul>
 *
 * <p>The last activity,
 * <code>com.android.notepad.TitleEditor</code>, allows the user to
 * edit the title of a note.  This could be implemented as a class that the
 * application directly invokes (by explicitly setting its component in
 * the Intent), but here we show a way you can publish alternative
 * operations on existing data:</p>
 *
 * <pre>
 * &lt;intent-filter android:label="@string/resolve_title"&gt;
 *     &lt;action android:name="<i>com.android.notepad.action.EDIT_TITLE</i>" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_DEFAULT android.intent.category.DEFAULT}" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_ALTERNATIVE android.intent.category.ALTERNATIVE}" /&gt;
 *     &lt;category android:name="{@link #CATEGORY_SELECTED_ALTERNATIVE android.intent.category.SELECTED_ALTERNATIVE}" /&gt;
 *     &lt;data android:mimeType="vnd.android.cursor.item/<i>vnd.google.note</i>" /&gt;
 * &lt;/intent-filter&gt;</pre>
 *
 * <p>In the single intent template here, we
 * have created our own private action called
 * <code>com.android.notepad.action.EDIT_TITLE</code> which means to
 * edit the title of a note.  It must be invoked on a specific note
 * (data type <code>vnd.android.cursor.item/vnd.google.note</code>) like the previous
 * view and edit actions, but here displays and edits the title contained
 * in the note data.
 *
 * <p>In addition to supporting the default category as usual, our title editor
 * also supports two other standard categories: ALTERNATIVE and
 * SELECTED_ALTERNATIVE.  Implementing
 * these categories allows others to find the special action it provides
 * without directly knowing about it, through the
 * {@link android.content.pm.PackageManager#queryIntentActivityOptions} method, or
 * more often to build dynamic menu items with
 * {@link android.view.Menu#addIntentOptions}.  Note that in the intent
 * template here was also supply an explicit name for the template
 * (via <code>android:label="@string/resolve_title"</code>) to better control
 * what the user sees when presented with this activity as an alternative
 * action to the data they are viewing.
 *
 * <p>Given these capabilities, the following intent will resolve to the
 * TitleEditor activity:</p>
 *
 * <ul>
 *     <li> <p><b>{ action=com.android.notepad.action.EDIT_TITLE
 *          data=content://com.google.provider.NotePad/notes/<var>{ID}</var> }</b>
 *         displays and allows the user to edit the title associated
 *         with note <var>{ID}</var>.</p>
 * </ul>
 *
 * <h3>Standard Activity Actions</h3>
 *
 * <p>These are the current standard actions that Intent defines for launching
 * activities (usually through {@link Context#startActivity}.  The most
 * important, and by far most frequently used, are {@link #ACTION_MAIN} and
 * {@link #ACTION_EDIT}.
 *
 * <ul>
 *     <li> {@link #ACTION_MAIN}
 *     <li> {@link #ACTION_VIEW}
 *     <li> {@link #ACTION_ATTACH_DATA}
 *     <li> {@link #ACTION_EDIT}
 *     <li> {@link #ACTION_PICK}
 *     <li> {@link #ACTION_CHOOSER}
 *     <li> {@link #ACTION_GET_CONTENT}
 *     <li> {@link #ACTION_DIAL}
 *     <li> {@link #ACTION_CALL}
 *     <li> {@link #ACTION_SEND}
 *     <li> {@link #ACTION_SENDTO}
 *     <li> {@link #ACTION_ANSWER}
 *     <li> {@link #ACTION_INSERT}
 *     <li> {@link #ACTION_DELETE}
 *     <li> {@link #ACTION_RUN}
 *     <li> {@link #ACTION_SYNC}
 *     <li> {@link #ACTION_PICK_ACTIVITY}
 *     <li> {@link #ACTION_SEARCH}
 *     <li> {@link #ACTION_WEB_SEARCH}
 *     <li> {@link #ACTION_FACTORY_TEST}
 * </ul>
 *
 * <h3>Standard Broadcast Actions</h3>
 *
 * <p>These are the current standard actions that Intent defines for receiving
 * broadcasts (usually through {@link Context#registerReceiver} or a
 * &lt;receiver&gt; tag in a manifest).
 *
 * <ul>
 *     <li> {@link #ACTION_TIME_TICK}
 *     <li> {@link #ACTION_TIME_CHANGED}
 *     <li> {@link #ACTION_TIMEZONE_CHANGED}
 *     <li> {@link #ACTION_BOOT_COMPLETED}
 *     <li> {@link #ACTION_PACKAGE_ADDED}
 *     <li> {@link #ACTION_PACKAGE_CHANGED}
 *     <li> {@link #ACTION_PACKAGE_REMOVED}
 *     <li> {@link #ACTION_PACKAGE_RESTARTED}
 *     <li> {@link #ACTION_PACKAGE_DATA_CLEARED}
 *     <li> {@link #ACTION_PACKAGES_SUSPENDED}
 *     <li> {@link #ACTION_PACKAGES_UNSUSPENDED}
 *     <li> {@link #ACTION_UID_REMOVED}
 *     <li> {@link #ACTION_BATTERY_CHANGED}
 *     <li> {@link #ACTION_POWER_CONNECTED}
 *     <li> {@link #ACTION_POWER_DISCONNECTED}
 *     <li> {@link #ACTION_SHUTDOWN}
 * </ul>
 *
 * <p class="note"><strong>Note: </strong>If your app targets Android 11
 * (API level 30) or higher, registering broadcast such as
 * {@link #ACTION_PACKAGES_SUSPENDED} that includes package details in the
 * extras receives a filtered list of apps or nothing. Learn more about how to
 * <a href="/training/basics/intents/package-visibility">manage package visibility</a>.
 * </p>
 *
 * <h3>Standard Categories</h3>
 *
 * <p>These are the current standard categories that can be used to further
 * clarify an Intent via {@link #addCategory}.
 *
 * <ul>
 *     <li> {@link #CATEGORY_DEFAULT}
 *     <li> {@link #CATEGORY_BROWSABLE}
 *     <li> {@link #CATEGORY_TAB}
 *     <li> {@link #CATEGORY_ALTERNATIVE}
 *     <li> {@link #CATEGORY_SELECTED_ALTERNATIVE}
 *     <li> {@link #CATEGORY_LAUNCHER}
 *     <li> {@link #CATEGORY_INFO}
 *     <li> {@link #CATEGORY_HOME}
 *     <li> {@link #CATEGORY_PREFERENCE}
 *     <li> {@link #CATEGORY_TEST}
 *     <li> {@link #CATEGORY_CAR_DOCK}
 *     <li> {@link #CATEGORY_DESK_DOCK}
 *     <li> {@link #CATEGORY_LE_DESK_DOCK}
 *     <li> {@link #CATEGORY_HE_DESK_DOCK}
 *     <li> {@link #CATEGORY_CAR_MODE}
 *     <li> {@link #CATEGORY_APP_MARKET}
 *     <li> {@link #CATEGORY_VR_HOME}
 * </ul>
 *
 * <h3>Standard Extra Data</h3>
 *
 * <p>These are the current standard fields that can be used as extra data via
 * {@link #putExtra}.
 *
 * <ul>
 *     <li> {@link #EXTRA_ALARM_COUNT}
 *     <li> {@link #EXTRA_BCC}
 *     <li> {@link #EXTRA_CC}
 *     <li> {@link #EXTRA_CHANGED_COMPONENT_NAME}
 *     <li> {@link #EXTRA_DATA_REMOVED}
 *     <li> {@link #EXTRA_DOCK_STATE}
 *     <li> {@link #EXTRA_DOCK_STATE_HE_DESK}
 *     <li> {@link #EXTRA_DOCK_STATE_LE_DESK}
 *     <li> {@link #EXTRA_DOCK_STATE_CAR}
 *     <li> {@link #EXTRA_DOCK_STATE_DESK}
 *     <li> {@link #EXTRA_DOCK_STATE_UNDOCKED}
 *     <li> {@link #EXTRA_DONT_KILL_APP}
 *     <li> {@link #EXTRA_EMAIL}
 *     <li> {@link #EXTRA_INITIAL_INTENTS}
 *     <li> {@link #EXTRA_INTENT}
 *     <li> {@link #EXTRA_KEY_EVENT}
 *     <li> {@link #EXTRA_ORIGINATING_URI}
 *     <li> {@link #EXTRA_PHONE_NUMBER}
 *     <li> {@link #EXTRA_REFERRER}
 *     <li> {@link #EXTRA_REMOTE_INTENT_TOKEN}
 *     <li> {@link #EXTRA_REPLACING}
 *     <li> {@link #EXTRA_SHORTCUT_ICON}
 *     <li> {@link #EXTRA_SHORTCUT_ICON_RESOURCE}
 *     <li> {@link #EXTRA_SHORTCUT_INTENT}
 *     <li> {@link #EXTRA_STREAM}
 *     <li> {@link #EXTRA_SHORTCUT_NAME}
 *     <li> {@link #EXTRA_SUBJECT}
 *     <li> {@link #EXTRA_TEMPLATE}
 *     <li> {@link #EXTRA_TEXT}
 *     <li> {@link #EXTRA_TITLE}
 *     <li> {@link #EXTRA_UID}
 *     <li> {@link #EXTRA_USER_INITIATED}
 * </ul>
 *
 * <h3>Flags</h3>
 *
 * <p>These are the possible flags that can be used in the Intent via
 * {@link #setFlags} and {@link #addFlags}.  See {@link #setFlags} for a list
 * of all possible flags.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class Intent implements Parcelable, Cloneable {
    private static final String TAG = "Intent";
    private static final String ATTR_ACTION = "action";
    private static final String TAG_CATEGORIES = "categories";
    private static final String ATTR_CATEGORY = "category";
    private static final String TAG_EXTRA = "extra";
    private static final String ATTR_TYPE = "type";
    private static final String ATTR_IDENTIFIER = "ident";
    private static final String ATTR_COMPONENT = "component";
    private static final String ATTR_DATA = "data";
    private static final String ATTR_FLAGS = "flags";

    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    // Standard intent activity actions (see action variable).

    /**
     *  Activity Action: Start as a main entry point, does not expect to
     *  receive data.
     *  <p>Input: nothing
     *  <p>Output: nothing
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MAIN = "android.intent.action.MAIN";

    /**
     * Activity Action: Display the data to the user.  This is the most common
     * action performed on data -- it is the generic action you can use on
     * a piece of data to get the most reasonable thing to occur.  For example,
     * when used on a contacts entry it will view the entry; when used on a
     * mailto: URI it will bring up a compose window filled with the information
     * supplied by the URI; when used with a tel: URI it will invoke the
     * dialer.
     * <p>Input: {@link #getData} is URI from which to retrieve data.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VIEW = "android.intent.action.VIEW";

    /**
     * Extra that can be included on activity intents coming from the storage UI
     * when it launches sub-activities to manage various types of storage.  For example,
     * it may use {@link #ACTION_VIEW} with a "image/*" MIME type to have an app show
     * the images on the device, and in that case also include this extra to tell the
     * app it is coming from the storage UI so should help the user manage storage of
     * this type.
     */
    public static final String EXTRA_FROM_STORAGE = "android.intent.extra.FROM_STORAGE";

    /**
     * A synonym for {@link #ACTION_VIEW}, the "standard" action that is
     * performed on a piece of data.
     */
    public static final String ACTION_DEFAULT = ACTION_VIEW;

    /**
     * Activity Action: Quick view the data. Launches a quick viewer for
     * a URI or a list of URIs.
     * <p>Activities handling this intent action should handle the vast majority of
     * MIME types rather than only specific ones.
     * <p>Quick viewers must render the quick view image locally, and must not send
     * file content outside current device.
     * <p>Input: {@link #getData} is a mandatory content URI of the item to
     * preview. {@link #getClipData} contains an optional list of content URIs
     * if there is more than one item to preview. {@link #EXTRA_INDEX} is an
     * optional index of the URI in the clip data to show first.
     * {@link #EXTRA_QUICK_VIEW_FEATURES} is an optional extra indicating the features
     * that can be shown in the quick view UI.
     * <p>Output: nothing.
     * @see #EXTRA_INDEX
     * @see #EXTRA_QUICK_VIEW_FEATURES
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_QUICK_VIEW = "android.intent.action.QUICK_VIEW";

    /**
     * Used to indicate that some piece of data should be attached to some other
     * place.  For example, image data could be attached to a contact.  It is up
     * to the recipient to decide where the data should be attached; the intent
     * does not specify the ultimate destination.
     * <p>Input: {@link #getData} is URI of data to be attached.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ATTACH_DATA = "android.intent.action.ATTACH_DATA";

    /**
     * Activity Action: Provide explicit editable access to the given data.
     * <p>Input: {@link #getData} is URI of data to be edited.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_EDIT = "android.intent.action.EDIT";

    /**
     * Activity Action: Pick an existing item, or insert a new item, and then edit it.
     * <p>Input: {@link #getType} is the desired MIME type of the item to create or edit.
     * The extras can contain type specific data to pass through to the editing/creating
     * activity.
     * <p>Output: The URI of the item that was picked.  This must be a content:
     * URI so that any receiver can access it.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_INSERT_OR_EDIT = "android.intent.action.INSERT_OR_EDIT";

    /**
     * Activity Action: Pick an item from the data, returning what was selected.
     * <p>Input: {@link #getData} is URI containing a directory of data
     * (vnd.android.cursor.dir/*) from which to pick an item.
     * <p>Output: The URI of the item that was picked.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PICK = "android.intent.action.PICK";

    /**
     * Activity Action: Creates a reminder.
     * <p>Input: {@link #EXTRA_TITLE} The title of the reminder that will be shown to the user.
     * {@link #EXTRA_TEXT} The reminder text that will be shown to the user. The intent should at
     * least specify a title or a text. {@link #EXTRA_TIME} The time when the reminder will
     * be shown to the user. The time is specified in milliseconds since the Epoch (optional).
     * </p>
     * <p>Output: Nothing.</p>
     *
     * @see #EXTRA_TITLE
     * @see #EXTRA_TEXT
     * @see #EXTRA_TIME
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CREATE_REMINDER = "android.intent.action.CREATE_REMINDER";

    /**
     * Activity Action: Creates a shortcut.
     * <p>Input: Nothing.</p>
     * <p>Output: An Intent representing the {@link android.content.pm.ShortcutInfo} result.</p>
     * <p>For compatibility with older versions of android the intent may also contain three
     * extras: SHORTCUT_INTENT (value: Intent), SHORTCUT_NAME (value: String),
     * and SHORTCUT_ICON (value: Bitmap) or SHORTCUT_ICON_RESOURCE
     * (value: ShortcutIconResource).</p>
     *
     * @see android.content.pm.ShortcutManager#createShortcutResultIntent
     * @see #EXTRA_SHORTCUT_INTENT
     * @see #EXTRA_SHORTCUT_NAME
     * @see #EXTRA_SHORTCUT_ICON
     * @see #EXTRA_SHORTCUT_ICON_RESOURCE
     * @see android.content.Intent.ShortcutIconResource
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CREATE_SHORTCUT = "android.intent.action.CREATE_SHORTCUT";

    /**
     * The name of the extra used to define the Intent of a shortcut.
     *
     * @see #ACTION_CREATE_SHORTCUT
     * @deprecated Replaced with {@link android.content.pm.ShortcutManager#createShortcutResultIntent}
     */
    @Deprecated
    public static final String EXTRA_SHORTCUT_INTENT = "android.intent.extra.shortcut.INTENT";
    /**
     * The name of the extra used to define the name of a shortcut.
     *
     * @see #ACTION_CREATE_SHORTCUT
     * @deprecated Replaced with {@link android.content.pm.ShortcutManager#createShortcutResultIntent}
     */
    @Deprecated
    public static final String EXTRA_SHORTCUT_NAME = "android.intent.extra.shortcut.NAME";
    /**
     * The name of the extra used to define the icon, as a Bitmap, of a shortcut.
     *
     * @see #ACTION_CREATE_SHORTCUT
     * @deprecated Replaced with {@link android.content.pm.ShortcutManager#createShortcutResultIntent}
     */
    @Deprecated
    public static final String EXTRA_SHORTCUT_ICON = "android.intent.extra.shortcut.ICON";
    /**
     * The name of the extra used to define the icon, as a ShortcutIconResource, of a shortcut.
     *
     * @see #ACTION_CREATE_SHORTCUT
     * @see android.content.Intent.ShortcutIconResource
     * @deprecated Replaced with {@link android.content.pm.ShortcutManager#createShortcutResultIntent}
     */
    @Deprecated
    public static final String EXTRA_SHORTCUT_ICON_RESOURCE =
            "android.intent.extra.shortcut.ICON_RESOURCE";

    /**
     * An activity that provides a user interface for adjusting application preferences.
     * Optional but recommended settings for all applications which have settings.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_APPLICATION_PREFERENCES
            = "android.intent.action.APPLICATION_PREFERENCES";

    /**
     * Activity Action: Launch an activity showing the app information.
     * For applications which install other applications (such as app stores), it is recommended
     * to handle this action for providing the app information to the user.
     *
     * <p>Input: {@link #EXTRA_PACKAGE_NAME} specifies the package whose information needs
     * to be displayed.
     * <p>Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SHOW_APP_INFO
            = "android.intent.action.SHOW_APP_INFO";

    /**
     * Activity Action: Placeholder that the component handling it can do activity
     * recognition. Can be placed on a service. Only one service per package is
     * supported.
     *
     * <p>Input: Nothing.</p>
     * <p>Output: Nothing </p>
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String ACTION_ACTIVITY_RECOGNIZER =
            "android.intent.action.ACTIVITY_RECOGNIZER";

    /** @hide */
    public static void maybeMarkAsMissingCreatorToken(Object object) {
        if (object instanceof Intent intent) {
            maybeMarkAsMissingCreatorTokenInternal(intent);
        } else if (object instanceof Parcelable[] parcelables) {
            for (Parcelable p : parcelables) {
                if (p instanceof Intent intent) {
                    maybeMarkAsMissingCreatorTokenInternal(intent);
                }
            }
        } else if (object instanceof ArrayList parcelables) {
            int N = parcelables.size();
            for (int i = 0; i < N; i++) {
                Object p = parcelables.get(i);
                if (p instanceof Intent intent) {
                    maybeMarkAsMissingCreatorTokenInternal(intent);
                }
            }
        }
    }

    private static void maybeMarkAsMissingCreatorTokenInternal(Intent intent) {
        boolean isForeign = (intent.mLocalFlags & LOCAL_FLAG_FROM_PARCEL) != 0;
        boolean isWithoutTrustedCreatorToken =
                (intent.mLocalFlags & Intent.LOCAL_FLAG_TRUSTED_CREATOR_TOKEN_PRESENT) == 0;
        if (isForeign && isWithoutTrustedCreatorToken && preventIntentRedirect()) {
            intent.addExtendedFlags(EXTENDED_FLAG_MISSING_CREATOR_OR_INVALID_TOKEN);
        }
    }

    /**
     * Represents a shortcut/live folder icon resource.
     *
     * @see Intent#ACTION_CREATE_SHORTCUT
     * @see Intent#EXTRA_SHORTCUT_ICON_RESOURCE
     * @see android.provider.LiveFolders#ACTION_CREATE_LIVE_FOLDER
     * @see android.provider.LiveFolders#EXTRA_LIVE_FOLDER_ICON
     */
    public static class ShortcutIconResource implements Parcelable {
        /**
         * The package name of the application containing the icon.
         */
        public String packageName;

        /**
         * The resource name of the icon, including package, name and type.
         */
        public String resourceName;

        /**
         * Creates a new ShortcutIconResource for the specified context and resource
         * identifier.
         *
         * @param context The context of the application.
         * @param resourceId The resource identifier for the icon.
         * @return A new ShortcutIconResource with the specified's context package name
         *         and icon resource identifier.``
         */
        public static ShortcutIconResource fromContext(Context context, @AnyRes int resourceId) {
            ShortcutIconResource icon = new ShortcutIconResource();
            icon.packageName = context.getPackageName();
            icon.resourceName = context.getResources().getResourceName(resourceId);
            return icon;
        }

        /**
         * Used to read a ShortcutIconResource from a Parcel.
         */
        public static final @android.annotation.NonNull Parcelable.Creator<ShortcutIconResource> CREATOR =
            new Parcelable.Creator<ShortcutIconResource>() {

                public ShortcutIconResource createFromParcel(Parcel source) {
                    ShortcutIconResource icon = new ShortcutIconResource();
                    icon.packageName = source.readString8();
                    icon.resourceName = source.readString8();
                    return icon;
                }

                public ShortcutIconResource[] newArray(int size) {
                    return new ShortcutIconResource[size];
                }
            };

        /**
         * No special parcel contents.
         */
        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString8(packageName);
            dest.writeString8(resourceName);
        }

        @Override
        public String toString() {
            return resourceName;
        }
    }

    /**
     * Activity Action: Display an activity chooser, allowing the user to pick
     * what they want to before proceeding.  This can be used as an alternative
     * to the standard activity picker that is displayed by the system when
     * you try to start an activity with multiple possible matches, with these
     * differences in behavior:
     * <ul>
     * <li>You can specify the title that will appear in the activity chooser.
     * <li>The user does not have the option to make one of the matching
     * activities a preferred activity, and all possible activities will
     * always be shown even if one of them is currently marked as the
     * preferred activity.
     * </ul>
     * <p>
     * This action should be used when the user will naturally expect to
     * select an activity in order to proceed.  An example if when not to use
     * it is when the user clicks on a "mailto:" link.  They would naturally
     * expect to go directly to their mail app, so startActivity() should be
     * called directly: it will
     * either launch the current preferred app, or put up a dialog allowing the
     * user to pick an app to use and optionally marking that as preferred.
     * <p>
     * In contrast, if the user is selecting a menu item to send a picture
     * they are viewing to someone else, there are many different things they
     * may want to do at this point: send it through e-mail, upload it to a
     * web service, etc.  In this case the CHOOSER action should be used, to
     * always present to the user a list of the things they can do, with a
     * nice title given by the caller such as "Send this photo with:".
     * <p>
     * If you need to grant URI permissions through a chooser, you must specify
     * the permissions to be granted on the ACTION_CHOOSER Intent
     * <em>in addition</em> to the EXTRA_INTENT inside.  This means using
     * {@link #setClipData} to specify the URIs to be granted as well as
     * {@link #FLAG_GRANT_READ_URI_PERMISSION} and/or
     * {@link #FLAG_GRANT_WRITE_URI_PERMISSION} as appropriate.
     * <p>
     * As a convenience, an Intent of this form can be created with the
     * {@link #createChooser} function.
     * <p>
     * Input: No data should be specified.  get*Extra must have
     * a {@link #EXTRA_INTENT} field containing the Intent being executed,
     * and can optionally have a {@link #EXTRA_TITLE} field containing the
     * title text to display in the chooser.
     * <p>
     * Output: Depends on the protocol of {@link #EXTRA_INTENT}.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CHOOSER = "android.intent.action.CHOOSER";

    /**
     * Convenience function for creating a {@link #ACTION_CHOOSER} Intent.
     *
     * <p>Builds a new {@link #ACTION_CHOOSER} Intent that wraps the given
     * target intent, also optionally supplying a title.  If the target
     * intent has specified {@link #FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link #FLAG_GRANT_WRITE_URI_PERMISSION}, then these flags will also be
     * set in the returned chooser intent, with its ClipData set appropriately:
     * either a direct reflection of {@link #getClipData()} if that is non-null,
     * or a new ClipData built from {@link #getData()}.
     *
     * @param target The Intent that the user will be selecting an activity
     * to perform.
     * @param title Optional title that will be displayed in the chooser,
     * only when the target action is not ACTION_SEND or ACTION_SEND_MULTIPLE.
     * @return Return a new Intent object that you can hand to
     * {@link Context#startActivity(Intent) Context.startActivity()} and
     * related methods.
     */
    public static Intent createChooser(Intent target, CharSequence title) {
        return createChooser(target, title, null);
    }

    /**
     * Convenience function for creating a {@link #ACTION_CHOOSER} Intent.
     *
     * <p>Builds a new {@link #ACTION_CHOOSER} Intent that wraps the given
     * target intent, also optionally supplying a title.  If the target
     * intent has specified {@link #FLAG_GRANT_READ_URI_PERMISSION} or
     * {@link #FLAG_GRANT_WRITE_URI_PERMISSION}, then these flags will also be
     * set in the returned chooser intent, with its ClipData set appropriately:
     * either a direct reflection of {@link #getClipData()} if that is non-null,
     * or a new ClipData built from {@link #getData()}.</p>
     *
     * <p>The caller may optionally supply an {@link IntentSender} to receive a callback
     * when the user makes a choice. This can be useful if the calling application wants
     * to remember the last chosen target and surface it as a more prominent or one-touch
     * affordance elsewhere in the UI for next time.</p>
     *
     * @param target The Intent that the user will be selecting an activity
     * to perform.
     * @param title Optional title that will be displayed in the chooser,
     * only when the target action is not ACTION_SEND or ACTION_SEND_MULTIPLE.
     * @param sender Optional IntentSender to be called when a choice is made.
     * @return Return a new Intent object that you can hand to
     * {@link Context#startActivity(Intent) Context.startActivity()} and
     * related methods.
     */
    public static Intent createChooser(Intent target, CharSequence title, IntentSender sender) {
        Intent intent = new Intent(ACTION_CHOOSER);
        intent.putExtra(EXTRA_INTENT, target);
        if (title != null) {
            intent.putExtra(EXTRA_TITLE, title);
        }

        if (sender != null) {
            intent.putExtra(EXTRA_CHOOSER_RESULT_INTENT_SENDER, sender);
        }

        // Migrate any clip data and flags from target.
        int permFlags = target.getFlags() & (FLAG_GRANT_READ_URI_PERMISSION
                | FLAG_GRANT_WRITE_URI_PERMISSION | FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (permFlags != 0) {
            ClipData targetClipData = target.getClipData();
            if (targetClipData == null && target.getData() != null) {
                ClipData.Item item = new ClipData.Item(target.getData());
                String[] mimeTypes;
                if (target.getType() != null) {
                    mimeTypes = new String[] { target.getType() };
                } else {
                    mimeTypes = new String[] { };
                }
                targetClipData = new ClipData(null, mimeTypes, item);
            }
            if (targetClipData != null) {
                intent.setClipData(targetClipData);
                intent.addFlags(permFlags);
            }
        }

        return intent;
    }

    /**
     * Activity Action: Allow the user to select a particular kind of data and
     * return it.  This is different than {@link #ACTION_PICK} in that here we
     * just say what kind of data is desired, not a URI of existing data from
     * which the user can pick.  An ACTION_GET_CONTENT could allow the user to
     * create the data as it runs (for example taking a picture or recording a
     * sound), let them browse over the web and download the desired data,
     * etc.
     * <p>
     * There are two main ways to use this action: if you want a specific kind
     * of data, such as a person contact, you set the MIME type to the kind of
     * data you want and launch it with {@link Context#startActivity(Intent)}.
     * The system will then launch the best application to select that kind
     * of data for you.
     * <p>
     * You may also be interested in any of a set of types of content the user
     * can pick.  For example, an e-mail application that wants to allow the
     * user to add an attachment to an e-mail message can use this action to
     * bring up a list of all of the types of content the user can attach.
     * <p>
     * In this case, you should wrap the GET_CONTENT intent with a chooser
     * (through {@link #createChooser}), which will give the proper interface
     * for the user to pick how to send your data and allow you to specify
     * a prompt indicating what they are doing.  You will usually specify a
     * broad MIME type (such as image/* or {@literal *}/*), resulting in a
     * broad range of content types the user can select from.
     * <p>
     * When using such a broad GET_CONTENT action, it is often desirable to
     * only pick from data that can be represented as a stream.  This is
     * accomplished by requiring the {@link #CATEGORY_OPENABLE} in the Intent.
     * <p>
     * Callers can optionally specify {@link #EXTRA_LOCAL_ONLY} to request that
     * the launched content chooser only returns results representing data that
     * is locally available on the device.  For example, if this extra is set
     * to true then an image picker should not show any pictures that are available
     * from a remote server but not already on the local device (thus requiring
     * they be downloaded when opened).
     * <p>
     * If the caller can handle multiple returned items (the user performing
     * multiple selection), then it can specify {@link #EXTRA_ALLOW_MULTIPLE}
     * to indicate this.
     * <p>
     * Input: {@link #getType} is the desired MIME type to retrieve.  Note
     * that no URI is supplied in the intent, as there are no constraints on
     * where the returned data originally comes from.  You may also include the
     * {@link #CATEGORY_OPENABLE} if you can only accept data that can be
     * opened as a stream.  You may use {@link #EXTRA_LOCAL_ONLY} to limit content
     * selection to local data.  You may use {@link #EXTRA_ALLOW_MULTIPLE} to
     * allow the user to select multiple items.
     * <p>
     * Output: The URI of the item that was picked.  This must be a content:
     * URI so that any receiver can access it.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT";
    /**
     * Activity Action: Dial a number as specified by the data.  This shows a
     * UI with the number being dialed, allowing the user to explicitly
     * initiate the call.
     * <p>Input: If nothing, an empty dialer is started; else {@link #getData}
     * is URI of a phone number to be dialed or a tel: URI of an explicit phone
     * number.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DIAL = "android.intent.action.DIAL";
    /**
     * Activity Action: Perform a call to someone specified by the data.
     * <p>Input: If nothing, an empty dialer is started; else {@link #getData}
     * is URI of a phone number to be dialed or a tel: URI of an explicit phone
     * number.
     * <p>Output: nothing.
     *
     * <p>Note: there will be restrictions on which applications can initiate a
     * call; most applications should use the {@link #ACTION_DIAL}.
     * <p>Note: this Intent <strong>cannot</strong> be used to call emergency
     * numbers.  Applications can <strong>dial</strong> emergency numbers using
     * {@link #ACTION_DIAL}, however.
     *
     * <p>Note: This Intent can only be used to dial call forwarding MMI codes if the application
     * using this intent is set as the default or system dialer. The system will treat any other
     * application using this Intent for the purpose of dialing call forwarding MMI codes as if the
     * {@link #ACTION_DIAL} Intent was used instead.
     *
     * <p>Note: An app filling the {@link android.app.role.RoleManager#ROLE_DIALER} role should use
     * {@link android.telecom.TelecomManager#placeCall(Uri, Bundle)} to place calls rather than
     * relying on this intent.
     *
     * <p>Note: If your app targets {@link android.os.Build.VERSION_CODES#M M}
     * or higher and declares as using the {@link android.Manifest.permission#CALL_PHONE}
     * permission which is not granted, then attempting to use this action will
     * result in a {@link java.lang.SecurityException}.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CALL = "android.intent.action.CALL";
    /**
     * Activity Action: Perform a call to an emergency number specified by the
     * data.
     * <p>Input: {@link #getData} is URI of a phone number to be dialed or a
     * tel: URI of an explicit phone number.
     * <p>Output: nothing.
     *
     * <p class="note"><strong>Note:</strong> It is not guaranteed that the call will be placed on
     * the {@link PhoneAccount} provided in the {@link TelecomManager#EXTRA_PHONE_ACCOUNT_HANDLE}
     * extra (if specified) and may be placed on another {@link PhoneAccount} with the
     * {@link PhoneAccount#CAPABILITY_PLACE_EMERGENCY_CALLS} capability, depending on external
     * factors, such as network conditions and Modem/SIM status.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CALL_EMERGENCY = "android.intent.action.CALL_EMERGENCY";
    /**
     * Activity Action: Dial a emergency number specified by the data.  This shows a
     * UI with the number being dialed, allowing the user to explicitly
     * initiate the call.
     * <p>Input: If nothing, an empty emergency dialer is started; else {@link #getData}
     * is a tel: URI of an explicit emergency phone number.
     * <p>Output: nothing.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DIAL_EMERGENCY = "android.intent.action.DIAL_EMERGENCY";
    /**
     * Activity action: Perform a call to any number (emergency or not)
     * specified by the data.
     * <p>Input: {@link #getData} is URI of a phone number to be dialed or a
     * tel: URI of an explicit phone number.
     * <p>Output: nothing.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CALL_PRIVILEGED = "android.intent.action.CALL_PRIVILEGED";

    /**
     * Activity Action: Main entry point for carrier setup apps.
     * <p>Carrier apps that provide an implementation for this action may be invoked to configure
     * carrier service and typically require
     * {@link android.telephony.TelephonyManager#hasCarrierPrivileges() carrier privileges} to
     * fulfill their duties.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CARRIER_SETUP = "android.intent.action.CARRIER_SETUP";
    /**
     * Activity Action: Send a message to someone specified by the data.
     * <p>Input: {@link #getData} is URI describing the target.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SENDTO = "android.intent.action.SENDTO";
    /**
     * Activity Action: Deliver some data to someone else.  Who the data is
     * being delivered to is not specified; it is up to the receiver of this
     * action to ask the user where the data should be sent.
     * <p>
     * When launching a SEND intent, you should usually wrap it in a chooser
     * (through {@link #createChooser}), which will give the proper interface
     * for the user to pick how to send your data and allow you to specify
     * a prompt indicating what they are doing.
     * <p>
     * Input: {@link #getType} is the MIME type of the data being sent.
     * get*Extra can have either a {@link #EXTRA_TEXT}
     * or {@link #EXTRA_STREAM} field, containing the data to be sent.  If
     * using EXTRA_TEXT, the MIME type should be "text/plain"; otherwise it
     * should be the MIME type of the data in EXTRA_STREAM.  Use {@literal *}/*
     * if the MIME type is unknown (this will only allow senders that can
     * handle generic data streams).  If using {@link #EXTRA_TEXT}, you can
     * also optionally supply {@link #EXTRA_HTML_TEXT} for clients to retrieve
     * your text with HTML formatting.
     * <p>
     * As of {@link android.os.Build.VERSION_CODES#JELLY_BEAN}, the data
     * being sent can be supplied through {@link #setClipData(ClipData)}.  This
     * allows you to use {@link #FLAG_GRANT_READ_URI_PERMISSION} when sharing
     * content: URIs and other advanced features of {@link ClipData}.  If
     * using this approach, you still must supply the same data through the
     * {@link #EXTRA_TEXT} or {@link #EXTRA_STREAM} fields described below
     * for compatibility with old applications.  If you don't set a ClipData,
     * it will be copied there for you when calling {@link Context#startActivity(Intent)}.
     * <p>
     * Starting from {@link android.os.Build.VERSION_CODES#O}, if
     * {@link #CATEGORY_TYPED_OPENABLE} is passed, then the Uris passed in
     * either {@link #EXTRA_STREAM} or via {@link #setClipData(ClipData)} may
     * be openable only as asset typed files using
     * {@link ContentResolver#openTypedAssetFileDescriptor(Uri, String, Bundle)}.
     * <p>
     * Optional standard extras, which may be interpreted by some recipients as
     * appropriate, are: {@link #EXTRA_EMAIL}, {@link #EXTRA_CC},
     * {@link #EXTRA_BCC}, {@link #EXTRA_SUBJECT}.
     * <p>
     * Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SEND = "android.intent.action.SEND";
    /**
     * Activity Action: Deliver multiple data to someone else.
     * <p>
     * Like {@link #ACTION_SEND}, except the data is multiple.
     * <p>
     * Input: {@link #getType} is the MIME type of the data being sent.
     * get*ArrayListExtra can have either a {@link #EXTRA_TEXT} or {@link
     * #EXTRA_STREAM} field, containing the data to be sent.  If using
     * {@link #EXTRA_TEXT}, you can also optionally supply {@link #EXTRA_HTML_TEXT}
     * for clients to retrieve your text with HTML formatting.
     * <p>
     * Multiple types are supported, and receivers should handle mixed types
     * whenever possible. The right way for the receiver to check them is to
     * use the content resolver on each URI. The intent sender should try to
     * put the most concrete mime type in the intent type, but it can fall
     * back to {@literal <type>/*} or {@literal *}/* as needed.
     * <p>
     * e.g. if you are sending image/jpg and image/jpg, the intent's type can
     * be image/jpg, but if you are sending image/jpg and image/png, then the
     * intent's type should be image/*.
     * <p>
     * As of {@link android.os.Build.VERSION_CODES#JELLY_BEAN}, the data
     * being sent can be supplied through {@link #setClipData(ClipData)}.  This
     * allows you to use {@link #FLAG_GRANT_READ_URI_PERMISSION} when sharing
     * content: URIs and other advanced features of {@link ClipData}.  If
     * using this approach, you still must supply the same data through the
     * {@link #EXTRA_TEXT} or {@link #EXTRA_STREAM} fields described below
     * for compatibility with old applications.  If you don't set a ClipData,
     * it will be copied there for you when calling {@link Context#startActivity(Intent)}.
     * <p>
     * Starting from {@link android.os.Build.VERSION_CODES#O}, if
     * {@link #CATEGORY_TYPED_OPENABLE} is passed, then the Uris passed in
     * either {@link #EXTRA_STREAM} or via {@link #setClipData(ClipData)} may
     * be openable only as asset typed files using
     * {@link ContentResolver#openTypedAssetFileDescriptor(Uri, String, Bundle)}.
     * <p>
     * Optional standard extras, which may be interpreted by some recipients as
     * appropriate, are: {@link #EXTRA_EMAIL}, {@link #EXTRA_CC},
     * {@link #EXTRA_BCC}, {@link #EXTRA_SUBJECT}.
     * <p>
     * Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE";
    /**
     * Activity Action: Handle an incoming phone call.
     * <p>Input: nothing.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ANSWER = "android.intent.action.ANSWER";
    /**
     * Activity Action: Insert an empty item into the given container.
     * <p>Input: {@link #getData} is URI of the directory (vnd.android.cursor.dir/*)
     * in which to place the data.
     * <p>Output: URI of the new data that was created.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_INSERT = "android.intent.action.INSERT";
    /**
     * Activity Action: Create a new item in the given container, initializing it
     * from the current contents of the clipboard.
     * <p>Input: {@link #getData} is URI of the directory (vnd.android.cursor.dir/*)
     * in which to place the data.
     * <p>Output: URI of the new data that was created.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PASTE = "android.intent.action.PASTE";
    /**
     * Activity Action: Delete the given data from its container.
     * <p>Input: {@link #getData} is URI of data to be deleted.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DELETE = "android.intent.action.DELETE";
    /**
     * Activity Action: Run the data, whatever that means.
     * <p>Input: ?  (Note: this is currently specific to the test harness.)
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_RUN = "android.intent.action.RUN";
    /**
     * Activity Action: Perform a data synchronization.
     * <p>Input: ?
     * <p>Output: ?
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SYNC = "android.intent.action.SYNC";
    /**
     * Activity Action: Pick an activity given an intent, returning the class
     * selected.
     * <p>Input: get*Extra field {@link #EXTRA_INTENT} is an Intent
     * used with {@link PackageManager#queryIntentActivities} to determine the
     * set of activities from which to pick.
     * <p>Output: Class name of the activity that was selected.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PICK_ACTIVITY = "android.intent.action.PICK_ACTIVITY";
    /**
     * Activity Action: Perform a search.
     * <p>Input: {@link android.app.SearchManager#QUERY getStringExtra(SearchManager.QUERY)}
     * is the text to search for.  If empty, simply
     * enter your search results Activity with the search UI activated.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SEARCH = "android.intent.action.SEARCH";
    /**
     * Activity Action: Start the platform-defined tutorial
     * <p>Input: {@link android.app.SearchManager#QUERY getStringExtra(SearchManager.QUERY)}
     * is the text to search for.  If empty, simply
     * enter your search results Activity with the search UI activated.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SYSTEM_TUTORIAL = "android.intent.action.SYSTEM_TUTORIAL";
    /**
     * Activity Action: Perform a web search.
     * <p>
     * Input: {@link android.app.SearchManager#QUERY
     * getStringExtra(SearchManager.QUERY)} is the text to search for. If it is
     * a url starts with http or https, the site will be opened. If it is plain
     * text, Google search will be applied.
     * <p>
     * Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_WEB_SEARCH = "android.intent.action.WEB_SEARCH";

    /**
     * Activity Action: Perform assist action.
     * <p>
     * Input: {@link #EXTRA_ASSIST_PACKAGE}, {@link #EXTRA_ASSIST_CONTEXT}, can provide
     * additional optional contextual information about where the user was when they
     * requested the assist; {@link #EXTRA_REFERRER} may be set with additional referrer
     * information.
     * Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ASSIST = "android.intent.action.ASSIST";

    /**
     * Activity Action: Perform voice assist action.
     * <p>
     * Input: {@link #EXTRA_ASSIST_PACKAGE}, {@link #EXTRA_ASSIST_CONTEXT}, can provide
     * additional optional contextual information about where the user was when they
     * requested the voice assist.
     * Output: nothing.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST";

    /**
     * An optional field on {@link #ACTION_ASSIST} containing the name of the current foreground
     * application package at the time the assist was invoked.
     */
    public static final String EXTRA_ASSIST_PACKAGE
            = "android.intent.extra.ASSIST_PACKAGE";

    /**
     * An optional field on {@link #ACTION_ASSIST} containing the uid of the current foreground
     * application package at the time the assist was invoked.
     */
    public static final String EXTRA_ASSIST_UID
            = "android.intent.extra.ASSIST_UID";

    /**
     * An optional field on {@link #ACTION_ASSIST} and containing additional contextual
     * information supplied by the current foreground app at the time of the assist request.
     * This is a {@link Bundle} of additional data.
     */
    public static final String EXTRA_ASSIST_CONTEXT
            = "android.intent.extra.ASSIST_CONTEXT";

    /**
     * An optional field on {@link #ACTION_ASSIST} suggesting that the user will likely use a
     * keyboard as the primary input device for assistance.
     */
    public static final String EXTRA_ASSIST_INPUT_HINT_KEYBOARD =
            "android.intent.extra.ASSIST_INPUT_HINT_KEYBOARD";

    /**
     * An optional field on {@link #ACTION_ASSIST} containing the InputDevice id
     * that was used to invoke the assist.
     */
    public static final String EXTRA_ASSIST_INPUT_DEVICE_ID =
            "android.intent.extra.ASSIST_INPUT_DEVICE_ID";

    /**
     * Activity Action: List all available applications.
     * <p>Input: Nothing.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ALL_APPS = "android.intent.action.ALL_APPS";

    /**
     * Activity Action: Action to show the list of all work apps in the launcher. For example,
     * shows the work apps folder or tab.
     *
     * <p>Input: Nothing.
     * <p>Output: nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SHOW_WORK_APPS =
            "android.intent.action.SHOW_WORK_APPS";

    /**
     * Activity Action: Show settings for choosing wallpaper.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SET_WALLPAPER = "android.intent.action.SET_WALLPAPER";

    /**
     * Activity Action: Show activity for reporting a bug.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_BUG_REPORT = "android.intent.action.BUG_REPORT";

    /**
     *  Activity Action: Main entry point for factory tests.  Only used when
     *  the device is booting in factory test node.  The implementing package
     *  must be installed in the system image.
     *  <p>Input: nothing
     *  <p>Output: nothing
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_FACTORY_TEST = "android.intent.action.FACTORY_TEST";

    /**
     * Activity Action: The user pressed the "call" button to go to the dialer
     * or other appropriate UI for placing a call.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CALL_BUTTON = "android.intent.action.CALL_BUTTON";

    /**
     * Activity Action: Start Voice Command.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     * <p class="note">
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VOICE_COMMAND = "android.intent.action.VOICE_COMMAND";

    /**
     * Activity Action: Start acti