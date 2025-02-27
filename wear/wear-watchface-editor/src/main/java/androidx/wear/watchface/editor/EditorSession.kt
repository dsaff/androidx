/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.wear.watchface.editor

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.wearable.complications.ComplicationProviderInfo
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.Px
import androidx.annotation.RequiresApi
import androidx.annotation.RestrictTo
import androidx.annotation.UiThread
import androidx.wear.complications.ComplicationHelperActivity
import androidx.wear.complications.ProviderInfoRetriever
import androidx.wear.complications.data.ComplicationData
import androidx.wear.complications.data.ComplicationType
import androidx.wear.complications.data.LongTextComplicationData
import androidx.wear.complications.data.MonochromaticImage
import androidx.wear.complications.data.PlainComplicationText
import androidx.wear.complications.data.ShortTextComplicationData
import androidx.wear.utility.AsyncTraceEvent
import androidx.wear.utility.TraceEvent
import androidx.wear.utility.launchWithTracing
import androidx.wear.watchface.RenderParameters
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.client.ComplicationState
import androidx.wear.watchface.client.EditorListener
import androidx.wear.watchface.client.EditorServiceClient
import androidx.wear.watchface.client.EditorState
import androidx.wear.watchface.client.HeadlessWatchFaceClient
import androidx.wear.watchface.client.WatchFaceId
import androidx.wear.watchface.data.ComplicationBoundsType
import androidx.wear.watchface.data.IdAndComplicationDataWireFormat
import androidx.wear.watchface.editor.data.EditorStateWireFormat
import androidx.wear.watchface.style.UserStyle
import androidx.wear.watchface.style.UserStyleData
import androidx.wear.watchface.style.UserStyleSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Interface for manipulating watch face state during an editing session for a watch face editing
 * session. The editor should adjust [userStyle] and call [openComplicationProviderChooser] to
 * configure the watch face and call [close] when done. This reports the updated [EditorState] to
 * the [EditorListener]s registered via [EditorServiceClient.addListener].
 */
public abstract class EditorSession : AutoCloseable {
    /** The [ComponentName] of the watch face being edited. */
    public abstract val watchFaceComponentName: ComponentName

    /**
     * Unique ID for the instance of the watch face being edited, only defined for Android R and
     * beyond, it's `null` on Android P and earlier. Note each distinct [ComponentName] can have
     * multiple instances.
     */
    @get:RequiresApi(Build.VERSION_CODES.R)
    public abstract val watchFaceId: WatchFaceId

    /** The current [UserStyle]. Assigning to this will cause the style to update. */
    public abstract var userStyle: UserStyle

    /** The UTC reference preview time for this watch face in milliseconds since the epoch. */
    public abstract val previewReferenceTimeMillis: Long

    /** The watch face's [UserStyleSchema]. */
    public abstract val userStyleSchema: UserStyleSchema

    /**
     * Map of complication ids to [ComplicationState] for each complication slot. Note
     * [ComplicationState] can change, typically in response to styling.
     */
    public abstract val complicationsState: Map<Int, ComplicationState>

    /**
     * Whether any changes should be committed when the session is closed (defaults to `true`).
     *
     * Note due to SysUI requirements [EditorState] can't reliably be sent in the activity result
     * because there are circumstances where [ComponentActivity.onStop] doesn't get called but the
     * UX requires us to commit changes.
     *
     * If false upon exit for an on watch face editor, the original UserStyle is restored. Note we
     * need SysUI's help to revert any complication provider changes. Caveat some providers have
     * their own config (e.g. the world clock has a timezone setting) and that config currently
     * can't be reverted.
     */
    @get:UiThread
    @get:JvmName("isCommitChangesOnClose")
    @set:UiThread
    public var commitChangesOnClose: Boolean = true

    /**
     * Returns a map of complication ids to preview [ComplicationData] suitable for use in rendering
     * the watch face. Note if a slot is configured to be empty then it will not appear in the map,
     * however disabled complications are included. Note also unlike live data this is static per
     * provider, but it may update (on the UiThread) as a result of
     * [openComplicationProviderChooser].
     */
    @UiThread
    public abstract suspend fun getComplicationsPreviewData(): Map<Int, ComplicationData>

    /** The ID of the background complication or `null` if there isn't one. */
    @get:SuppressWarnings("AutoBoxing")
    public abstract val backgroundComplicationId: Int?

    /**
     * Returns the ID of the complication at the given coordinates or `null` if there isn't one.
     * Only complications with [ComplicationBoundsType.ROUND_RECT] are supported by this function.
     */
    @SuppressWarnings("AutoBoxing")
    @UiThread
    public abstract fun getComplicationIdAt(@Px x: Int, @Px y: Int): Int?

    /**
     * Renders the watch face to a [Bitmap] using the current [userStyle].
     *
     * @param renderParameters The [RenderParameters] to render with
     * @param calendarTimeMillis The UTC time in milliseconds since the epoch to render with
     * @param idToComplicationData The [ComplicationData] for each complication to render with
     */
    @UiThread
    public abstract fun renderWatchFaceToBitmap(
        renderParameters: RenderParameters,
        calendarTimeMillis: Long,
        idToComplicationData: Map<Int, ComplicationData>?
    ): Bitmap

    /**
     * Opens the complication provider chooser and returns `true` if the user made a selection or
     * `false` if the activity was canceled. If the complication provider was changed then the map
     * returned by [getComplicationsPreviewData] is updated (on the UiThread).
     */
    @UiThread
    public abstract suspend fun openComplicationProviderChooser(complicationId: Int): Boolean

    public companion object {
        /**
         * Constructs an [EditorSession] for an on watch face editor. This registers an activity
         * result handler and so it must be called during an Activity or Fragment initialization
         * path.
         *
         * @param activity The [ComponentActivity] associated with the [EditorSession].
         * @param editIntent The [Intent] sent by SysUI to launch the editing session.
         * @return Deferred<EditorSession?> which is resolved with either the [EditorSession] or
         *    `null` if it can't be constructed.
         */
        @SuppressWarnings("ExecutorRegistration")
        @JvmStatic
        @UiThread
        public suspend fun createOnWatchEditingSession(
            activity: ComponentActivity,
            editIntent: Intent
        ): EditorSession? = createOnWatchEditingSessionImpl(
            activity,
            editIntent,
            object : ProviderInfoRetrieverProvider {
                override fun getProviderInfoRetriever() = ProviderInfoRetriever(activity)
            }
        )

        // Used by tests.
        internal suspend fun createOnWatchEditingSessionImpl(
            activity: ComponentActivity,
            editIntent: Intent,
            providerInfoRetrieverProvider: ProviderInfoRetrieverProvider
        ): EditorSession? = TraceEvent(
            "EditorSession.createOnWatchEditingSessionAsyncImpl"
        ).use {
            val coroutineScope =
                CoroutineScope(Handler(Looper.getMainLooper()).asCoroutineDispatcher().immediate)
            return EditorRequest.createFromIntent(editIntent)?.let { editorRequest ->
                // We need to respect the lifecycle and register the ActivityResultListener now.
                val session = OnWatchFaceEditorSessionImpl(
                    activity,
                    editorRequest.watchFaceComponentName,
                    editorRequest.watchFaceId,
                    editorRequest.initialUserStyle,
                    providerInfoRetrieverProvider,
                    coroutineScope
                )

                // But full initialization has to be deferred because
                // [WatchFace.getOrCreateEditorDelegate] is async.
                // Resolve only after init has been completed.
                withContext(coroutineScope.coroutineContext) {
                    session.setEditorDelegate(
                        WatchFace.getOrCreateEditorDelegate(
                            editorRequest.watchFaceComponentName
                        ).await()!!
                    )

                    // Resolve only after init has been completed.
                    session
                }
            }
        }

        /**
         * Constructs an [EditorSession] for a remote watch face editor.
         *
         * @param activity The [ComponentActivity] associated with the EditorSession.
         * @param editIntent The [Intent] sent by SysUI to launch the editing session.
         * @param headlessWatchFaceClient The [HeadlessWatchFaceClient] to use for rendering etc...
         * @return The [EditorSession] or `null` if it could not be constructed.
         */
        @JvmStatic
        @RequiresApi(27)
        @UiThread
        public fun createHeadlessEditingSession(
            activity: ComponentActivity,
            editIntent: Intent,
            headlessWatchFaceClient: HeadlessWatchFaceClient
        ): EditorSession? = TraceEvent("EditorSession.createHeadlessEditingSession").use {
            EditorRequest.createFromIntent(editIntent)?.let {
                HeadlessEditorSession(
                    activity,
                    headlessWatchFaceClient,
                    it.watchFaceComponentName,
                    it.watchFaceId,
                    it.initialUserStyle!!,
                    object : ProviderInfoRetrieverProvider {
                        override fun getProviderInfoRetriever() = ProviderInfoRetriever(activity)
                    },
                    CoroutineScope(
                        Handler(Looper.getMainLooper()).asCoroutineDispatcher().immediate
                    )
                )
            }
        }
    }
}

// Helps inject mock ProviderInfoRetrievers for testing.
internal interface ProviderInfoRetrieverProvider {
    fun getProviderInfoRetriever(): ProviderInfoRetriever
}

/**
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public abstract class BaseEditorSession internal constructor(
    private val activity: ComponentActivity,
    private val providerInfoRetrieverProvider: ProviderInfoRetrieverProvider,
    public val coroutineScope: CoroutineScope
) : EditorSession() {
    protected var closed: Boolean = false
    protected var forceClosed: Boolean = false

    private val editorSessionTraceEvent = AsyncTraceEvent("EditorSession")
    private val closeCallback = object : EditorService.CloseCallback() {
        override fun onClose() {
            forceClose()
        }
    }

    private companion object {
        /** Timeout for fetching ComplicationsPreviewData in [BaseEditorSession.close]. */
        private const val CLOSE_BROADCAST_TIMEOUT_MILLIS = 500L
    }

    init {
        EditorService.globalEditorService.addCloseCallback(closeCallback)
    }

    // This is completed when [fetchComplicationPreviewData] has called [getPreviewData] for
    // each complication and each of those have been completed.
    private val deferredComplicationPreviewDataMap =
        CompletableDeferred<MutableMap<Int, ComplicationData>>()

    override suspend fun getComplicationsPreviewData(): Map<Int, ComplicationData> {
        return deferredComplicationPreviewDataMap.await()
    }

    // Pending result for [launchComplicationProviderChooser].
    internal var pendingComplicationProviderChooserResult: CompletableDeferred<Boolean>? = null

    // The id of the complication being configured due to [launchComplicationProviderChooser].
    private var pendingComplicationProviderId: Int = -1

    private val chooseComplicationProvider =
        activity.registerForActivityResult(ComplicationProviderChooserContract()) {
            updatePreviewData(it)
        }

    internal fun updatePreviewData(
        complicationProviderChooserResult: ComplicationProviderChooserResult
    ) {
        val providerInfoRetriever =
            providerInfoRetrieverProvider.getProviderInfoRetriever()
        coroutineScope.launchWithTracing("BaseEditorSession.updatePreviewData") {
            try {
                val previewData = getPreviewData(
                    providerInfoRetriever,
                    complicationProviderChooserResult.providerInfo
                )
                val complicationPreviewDataMap = deferredComplicationPreviewDataMap.await()
                if (previewData == null) {
                    complicationPreviewDataMap.remove(pendingComplicationProviderId)
                } else {
                    complicationPreviewDataMap[pendingComplicationProviderId] = previewData
                }
                pendingComplicationProviderChooserResult!!.complete(true)
                pendingComplicationProviderChooserResult = null
            } finally {
                // This gets called after the above coroutine has finished.
                providerInfoRetriever.close()
            }
        }
    }

    override suspend fun openComplicationProviderChooser(
        complicationId: Int
    ): Boolean = TraceEvent(
        "BaseEditorSession.launchComplicationProviderChooser $complicationId"
    ).use {
        requireNotClosed()
        require(!complicationsState[complicationId]!!.fixedComplicationProvider) {
            "Can't configure fixed complication ID $complicationId"
        }
        pendingComplicationProviderChooserResult = CompletableDeferred<Boolean>()
        pendingComplicationProviderId = complicationId
        chooseComplicationProvider.launch(
            ComplicationProviderChooserRequest(this, complicationId, watchFaceId.id)
        )
        return pendingComplicationProviderChooserResult!!.await()
    }

    override val backgroundComplicationId: Int? by lazy {
        requireNotClosed()
        complicationsState.entries.firstOrNull {
            it.value.boundsType == ComplicationBoundsType.BACKGROUND
        }?.key
    }

    override fun getComplicationIdAt(@Px x: Int, @Px y: Int): Int? {
        requireNotClosed()
        return complicationsState.entries.firstOrNull {
            it.value.isEnabled && when (it.value.boundsType) {
                ComplicationBoundsType.ROUND_RECT -> it.value.bounds.contains(x, y)
                ComplicationBoundsType.BACKGROUND -> false
                ComplicationBoundsType.EDGE -> false
                else -> false
            }
        }?.key
    }

    /**
     * Returns the provider's preview [ComplicationData] if possible or fallback preview data based
     * on provider icon and name if not. If the slot is configured to be empty then it will return
     * `null`.
     *
     * Note providerInfoRetriever.requestPreviewComplicationData which requires R will never be
     * called pre R because providerInfo.providerComponentName is only non null from R onwards.
     */
    @SuppressLint("NewApi")
    internal suspend fun getPreviewData(
        providerInfoRetriever: ProviderInfoRetriever,
        providerInfo: ComplicationProviderInfo?
    ): ComplicationData? = TraceEvent("BaseEditorSession.getPreviewData").use {
        if (providerInfo == null) {
            return null
        }
        // Fetch preview ComplicationData if possible.
        return providerInfo.providerComponentName?.let {
            try {
                providerInfoRetriever.retrievePreviewComplicationData(
                    it,
                    ComplicationType.fromWireType(providerInfo.complicationType)
                )
            } catch (e: Exception) {
                // Something went wrong, so use fallback preview data.
                makeFallbackPreviewData(providerInfo)
            }
        } ?: makeFallbackPreviewData(providerInfo)
    }

    private fun makeFallbackPreviewData(
        providerInfo: ComplicationProviderInfo
    ) = when {
        providerInfo.providerName == null -> null

        providerInfo.providerIcon == null ->
            LongTextComplicationData.Builder(
                PlainComplicationText.Builder(providerInfo.providerName!!).build()
            ).build()

        else ->
            ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(providerInfo.providerName!!).build()
            ).setMonochromaticImage(
                MonochromaticImage.Builder(providerInfo.providerIcon!!).build()
            ).build()
    }

    protected fun fetchComplicationPreviewData() {
        val providerInfoRetriever = providerInfoRetrieverProvider.getProviderInfoRetriever()
        coroutineScope.launchWithTracing("BaseEditorSession.fetchComplicationPreviewData") {
            try {
                // Unlikely but WCS could conceivably crash during this call. We could retry but it's
                // not obvious if that'd succeed or if WCS session state is recoverable, it's probably
                // better to crash and start over.
                val providerInfoArray = providerInfoRetriever.retrieveProviderInfo(
                    watchFaceComponentName,
                    complicationsState.keys.toIntArray()
                )
                deferredComplicationPreviewDataMap.complete(
                    // Parallel fetch preview ComplicationData.
                    providerInfoArray?.associateBy(
                        { it.watchFaceComplicationId },
                        { async { getPreviewData(providerInfoRetriever, it.info) } }
                        // Coerce to a Map<Int, ComplicationData> omitting null values.
                        // If mapNotNullValues existed we would use it here.
                    )?.filterValues {
                        it.await() != null
                    }?.mapValues {
                        it.value.await()!!
                    }?.toMutableMap() ?: mutableMapOf()
                )
            } finally {
                providerInfoRetriever.close()
            }
        }
    }

    override fun close() {
        // Silently do nothing if we've been force closed, this simplifies the editor activity.
        if (forceClosed) {
            return
        }
        requireNotClosed()
        EditorService.globalEditorService.removeCloseCallback(closeCallback)
        // We need to send the preview data which we obtain asynchronously.
        coroutineScope.launchWithTracing("BaseEditorSession.close") {
            try {
                withTimeout(CLOSE_BROADCAST_TIMEOUT_MILLIS) {
                    EditorService.globalEditorService.broadcastEditorState(
                        EditorStateWireFormat(
                            watchFaceId.id,
                            userStyle.toWireFormat(),
                            getComplicationsPreviewData().map {
                                IdAndComplicationDataWireFormat(
                                    it.key,
                                    it.value.asWireComplicationData()
                                )
                            },
                            commitChangesOnClose
                        )
                    )
                }
            } catch (e: TimeoutCancellationException) {
                // Ignore this, nothing we can do.
            }

            releaseResources()
            closed = true
            editorSessionTraceEvent.close()
            coroutineScope.cancel()
        }
    }

    @UiThread
    internal fun forceClose() {
        commitChangesOnClose = false
        closed = true
        forceClosed = true
        releaseResources()
        activity.finish()
        EditorService.globalEditorService.removeCloseCallback(closeCallback)
        editorSessionTraceEvent.close()
        coroutineScope.cancel()
    }

    protected fun requireNotClosed() {
        require(!closed or forceClosed) {
            "EditorSession method called after close()"
        }
    }

    @UiThread
    protected abstract fun releaseResources()
}

internal class OnWatchFaceEditorSessionImpl(
    activity: ComponentActivity,
    override val watchFaceComponentName: ComponentName,
    override val watchFaceId: WatchFaceId,
    private val initialEditorUserStyle: UserStyleData?,
    providerInfoRetrieverProvider: ProviderInfoRetrieverProvider,
    coroutineScope: CoroutineScope
) : BaseEditorSession(activity, providerInfoRetrieverProvider, coroutineScope) {
    private lateinit var editorDelegate: WatchFace.EditorDelegate

    override val userStyleSchema by lazy {
        requireNotClosed()
        editorDelegate.userStyleSchema
    }

    override val previewReferenceTimeMillis by lazy { editorDelegate.previewReferenceTimeMillis }

    override val complicationsState
        get() = editorDelegate.complicationsManager.complications.mapValues {
            requireNotClosed()
            ComplicationState(
                it.value.computeBounds(editorDelegate.screenBounds),
                it.value.boundsType,
                it.value.supportedTypes,
                it.value.defaultProviderPolicy,
                it.value.defaultProviderType,
                it.value.enabled,
                it.value.initiallyEnabled,
                it.value.renderer.getData()?.type ?: ComplicationType.NO_DATA,
                it.value.fixedComplicationProvider,
                it.value.configExtras
            )
        }

    private var _userStyle: UserStyle? = null

    // We make a deep copy of the style because assigning to it can otherwise have unexpected
    // side effects (it would apply to the active watch face).
    override var userStyle: UserStyle
        get() {
            requireNotClosed()
            if (_userStyle == null) {
                _userStyle = UserStyle(editorDelegate.userStyle)
            }
            return _userStyle!!
        }
        set(value) {
            requireNotClosed()
            _userStyle = value
            editorDelegate.userStyle = UserStyle(value)
        }

    private lateinit var previousWatchFaceUserStyle: UserStyle

    override fun renderWatchFaceToBitmap(
        renderParameters: RenderParameters,
        calendarTimeMillis: Long,
        idToComplicationData: Map<Int, ComplicationData>?
    ): Bitmap {
        requireNotClosed()
        return editorDelegate.renderWatchFaceToBitmap(
            renderParameters,
            calendarTimeMillis,
            idToComplicationData
        )
    }

    override fun releaseResources() {
        if (this::editorDelegate.isInitialized) {
            editorDelegate.onDestroy()
        }
        // Revert any changes to the UserStyle if needed.
        if (!commitChangesOnClose && this::previousWatchFaceUserStyle.isInitialized) {
            userStyle = previousWatchFaceUserStyle
        }
    }

    fun setEditorDelegate(editorDelegate: WatchFace.EditorDelegate) {
        this.editorDelegate = editorDelegate

        previousWatchFaceUserStyle = UserStyle(editorDelegate.userStyle)

        // Apply any initial style from the intent.  Note we don't restore the previous style at
        // the end since we assume we're editing the current active watchface.
        if (initialEditorUserStyle != null) {
            editorDelegate.userStyle =
                UserStyle(initialEditorUserStyle, editorDelegate.userStyleSchema)
        }

        fetchComplicationPreviewData()
    }
}

@RequiresApi(27)
internal class HeadlessEditorSession(
    activity: ComponentActivity,
    private val headlessWatchFaceClient: HeadlessWatchFaceClient,
    override val watchFaceComponentName: ComponentName,
    override val watchFaceId: WatchFaceId,
    initialUserStyle: UserStyleData,
    providerInfoRetrieverProvider: ProviderInfoRetrieverProvider,
    coroutineScope: CoroutineScope,
) : BaseEditorSession(activity, providerInfoRetrieverProvider, coroutineScope) {
    override val userStyleSchema = headlessWatchFaceClient.userStyleSchema

    override var userStyle = UserStyle(initialUserStyle, userStyleSchema)

    override val previewReferenceTimeMillis = headlessWatchFaceClient.previewReferenceTimeMillis

    override val complicationsState = headlessWatchFaceClient.complicationsState

    override fun renderWatchFaceToBitmap(
        renderParameters: RenderParameters,
        calendarTimeMillis: Long,
        idToComplicationData: Map<Int, ComplicationData>?
    ): Bitmap {
        requireNotClosed()
        return headlessWatchFaceClient.renderWatchFaceToBitmap(
            renderParameters,
            calendarTimeMillis,
            userStyle,
            idToComplicationData
        )
    }

    override fun releaseResources() {
        headlessWatchFaceClient.close()
    }

    init {
        fetchComplicationPreviewData()
    }
}

internal class ComplicationProviderChooserRequest(
    internal val editorSession: EditorSession,
    internal val complicationId: Int,
    internal val instanceId: String?
)

internal class ComplicationProviderChooserResult(
    /** The updated [ComplicationProviderInfo] or `null` if the operation was canceled. */
    internal val providerInfo: ComplicationProviderInfo?
)

/** An [ActivityResultContract] for invoking the complication provider chooser. */
internal class ComplicationProviderChooserContract : ActivityResultContract<
    ComplicationProviderChooserRequest, ComplicationProviderChooserResult>() {

    internal companion object {
        const val EXTRA_PROVIDER_INFO = "android.support.wearable.complications.EXTRA_PROVIDER_INFO"
        internal var useTestComplicationHelperActivity = false
    }

    override fun createIntent(context: Context, input: ComplicationProviderChooserRequest): Intent {
        val intent = ComplicationHelperActivity.createProviderChooserHelperIntent(
            context,
            input.editorSession.watchFaceComponentName,
            input.complicationId,
            input.editorSession.complicationsState[input.complicationId]!!.supportedTypes,
            input.instanceId
        )
        val complicationState = input.editorSession.complicationsState[input.complicationId]!!
        intent.replaceExtras(
            Bundle(complicationState.complicationConfigExtras).apply { putAll(intent.extras!!) }
        )
        if (useTestComplicationHelperActivity) {
            intent.component = ComponentName(
                "androidx.wear.watchface.editor.test",
                "androidx.wear.watchface.editor.TestComplicationHelperActivity"
            )
        }
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): ComplicationProviderChooserResult {
        return ComplicationProviderChooserResult(intent?.getParcelableExtra(EXTRA_PROVIDER_INFO))
    }
}
