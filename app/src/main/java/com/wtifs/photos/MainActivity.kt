package com.wtifs.photos

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhotosApp() }
    }
}

data class PhotoAsset(val uri: Uri, val takenAt: Long, val displayName: String) {
    val mayContainLiveClip: Boolean get() = displayName.startsWith("MVIMG_", ignoreCase = true)
}

class PhotosViewModel(application: Application) : AndroidViewModel(application) {
    var photos by mutableStateOf<List<PhotoAsset>>(emptyList())
        private set

    suspend fun loadPhotos() {
        photos = withContext(Dispatchers.IO) {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DISPLAY_NAME)
            getApplication<Application>().contentResolver.query(collection, projection, null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                buildList {
                    while (cursor.moveToNext()) add(PhotoAsset(ContentUris.withAppendedId(collection, cursor.getLong(idColumn)), cursor.getLong(dateColumn), cursor.getString(nameColumn)))
                }
            } ?: emptyList()
        }
    }
}

@Composable
private fun PhotosApp(photosViewModel: PhotosViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    var accessGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val libraryGridState = rememberLazyGridState()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { accessGranted = it }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch { photosViewModel.loadPhotos() }
            viewerIndex = null
        }
    }

    LaunchedEffect(accessGranted) { if (accessGranted) photosViewModel.loadPhotos() }
    val requestDelete: (List<PhotoAsset>) -> Unit = { assets ->
        val request = MediaStore.createDeleteRequest(context.contentResolver, assets.map { it.uri })
        deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                !accessGranted -> PermissionView { permissionLauncher.launch(permission) }
                photosViewModel.photos.isEmpty() -> EmptyGalleryView()
                viewerIndex == null -> LibraryScreen(photosViewModel.photos, libraryGridState, onOpen = { viewerIndex = it }, onDelete = requestDelete)
                else -> PhotoViewer(
                    photos = photosViewModel.photos,
                    initialIndex = viewerIndex!!.coerceIn(0, photosViewModel.photos.lastIndex),
                    onPageChanged = { viewerIndex = it },
                    onClose = { viewerIndex = null },
                    onDelete = { requestDelete(listOf(it)) },
                )
            }
        }
    }
}

@Composable
private fun PermissionView(onAllow: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("Photos", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text("Allow access to view your photo library.", color = Color(0xFFB8B8B8))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAllow) { Text("Allow Photos Access") }
    }
}

@Composable
private fun EmptyGalleryView() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text("No photos found", color = Color(0xFFB8B8B8), fontSize = 17.sp)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryScreen(photos: List<PhotoAsset>, gridState: LazyGridState, onOpen: (Int) -> Unit, onDelete: (List<PhotoAsset>) -> Unit) {
    var selectedUris by remember { mutableStateOf(setOf<Uri>()) }
    val selecting = selectedUris.isNotEmpty()
    Column(Modifier.fillMaxSize().background(Color(0xFF101010))) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selecting) "${selectedUris.size} Selected" else "Library", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (selecting) {
                IconButton(onClick = { selectedUris = photos.map { it.uri }.toSet() }) { Icon(Icons.Outlined.SelectAll, "Select all", tint = Color.White) }
                IconButton(onClick = { onDelete(photos.filter { it.uri in selectedUris }); selectedUris = emptySet() }) { Icon(Icons.Outlined.DeleteOutline, "Delete selected", tint = Color(0xFFFF6B5E)) }
            } else {
                IconButton(onClick = {}) { Icon(Icons.Outlined.MoreHoriz, "More library actions", tint = Color.White) }
            }
        }
        Text("${photos.size} Photos", color = Color(0xFF969696), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(3), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            items(photos, key = { it.uri }) { asset ->
                val selected = asset.uri in selectedUris
                Box(
                    Modifier.padding(1.dp).height(132.dp).fillMaxWidth()
                        .pointerInput(selecting) {
                            detectTapGestures(
                                onTap = { if (selecting) selectedUris = selectedUris.toggle(asset.uri) else onOpen(photos.indexOf(asset)) },
                                onLongPress = { selectedUris = selectedUris.toggle(asset.uri) },
                            )
                        },
                ) {
                    AsyncImage(ImageRequest.Builder(LocalContext.current).data(asset.uri).size(400).build(), "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (asset.mayContainLiveClip) {
                        Box(
                            Modifier.align(Alignment.TopStart).padding(7.dp).size(24.dp).clip(RoundedCornerShape(50)).background(Color(0xAA000000)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.PlayArrow, "Live photo", tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                    if (selected) Box(Modifier.fillMaxSize().background(Color(0x550A84FF)))
                    if (selecting) SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SelectionBadge(selected: Boolean, modifier: Modifier = Modifier) = Box(
    modifier.size(22.dp).clip(RoundedCornerShape(50)).background(if (selected) Color(0xFF0A84FF) else Color(0x99000000)),
    contentAlignment = Alignment.Center,
) { if (selected) Text("✓", color = Color.White, fontWeight = FontWeight.Bold) }

private fun Set<Uri>.toggle(uri: Uri): Set<Uri> = if (uri in this) this - uri else this + uri

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoViewer(photos: List<PhotoAsset>, initialIndex: Int, onPageChanged: (Int) -> Unit, onClose: () -> Unit, onDelete: (PhotoAsset) -> Unit) {
    var isDetail by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (isDetail) isDetail = false else onClose() }
    val context = LocalContext.current
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = initialIndex, pageCount = { photos.size })
    val filmstripState = rememberLazyListState()
    var playingAsset by remember { mutableStateOf<PhotoAsset?>(null) }
    var clipUri by remember { mutableStateOf<Uri?>(null) }
    var centeringFilmstrip by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val selected = pagerState.currentPage
    LaunchedEffect(selected) {
        onPageChanged(selected)
        zoom = 1f
        pan = Offset.Zero
        centeringFilmstrip = true
        try { centerFilmstripItem(filmstripState, selected) } finally { centeringFilmstrip = false }
    }
    LaunchedEffect(filmstripState) {
        snapshotFlow { filmstripState.centeredItemIndex() }
            .distinctUntilChanged()
            .collect { index ->
                if (!centeringFilmstrip && index != null && index != pagerState.currentPage) pagerState.requestScrollToPage(index)
            }
    }
    LaunchedEffect(playingAsset) {
        clipUri = playingAsset?.let { asset -> withContext(Dispatchers.IO) { LiveClipExtractor.extract(context, asset) } }
    }

    Box(Modifier.fillMaxSize().background(if (isDetail) Color.Black else Color.White)) {
        if (isDetail) {
            DetailPhoto(
                asset = photos[selected],
                zoom = zoom,
                pan = pan,
                onTransform = { zoomChange, panChange ->
                    zoom = (zoom * zoomChange).coerceIn(1f, 5f)
                    pan = if (zoom > 1f) pan + panChange else Offset.Zero
                },
                onLongPress = { if (photos[selected].mayContainLiveClip) playingAsset = photos[selected] },
            )
        } else {
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(photos[page].uri).crossfade(true).build(),
                    contentDescription = "Photo ${page + 1}",
                    modifier = Modifier.fillMaxSize().pointerInput(page) {
                        detectTapGestures(
                            onTap = { isDetail = true },
                            onLongPress = { if (photos[page].mayContainLiveClip) playingAsset = photos[page] },
                        )
                    },
                    contentScale = ContentScale.Fit,
                )
            }
        }
        AnimatedVisibility(visible = !isDetail, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                ViewerHeader(
                    asset = photos[selected],
                    onBack = onClose,
                    onPlay = if (photos[selected].mayContainLiveClip) ({ playingAsset = photos[selected] }) else null,
                    onDelete = { onDelete(photos[selected]) },
                )
                Box(Modifier.align(Alignment.BottomCenter)) {
                    ViewerBottomBar(
                        photos = photos,
                        selected = selected,
                        filmstripState = filmstripState,
                        onSelect = { pagerState.requestScrollToPage(it) },
                    )
                }
            }
        }
        clipUri?.let { uri -> LiveClipPlayer(uri) { playingAsset = null; clipUri = null } }
    }
}

@Composable
private fun DetailPhoto(asset: PhotoAsset, zoom: Float, pan: Offset, onTransform: (Float, Offset) -> Unit, onLongPress: () -> Unit) {
    val transformState = rememberTransformableState { zoomChange, panChange, _ -> onTransform(zoomChange, panChange) }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(asset.uri).crossfade(true).build(),
        contentDescription = "Photo detail",
        modifier = Modifier.fillMaxSize()
            .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y)
            .transformable(transformState)
            .pointerInput(asset.uri) { detectTapGestures(onLongPress = { onLongPress() }) },
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun ViewerHeader(asset: PhotoAsset, onBack: () -> Unit, onPlay: (() -> Unit)?, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(96.dp).background(Color(0xEFFFFFFF)).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewerCircleButton(Icons.Outlined.ArrowBack, "Back to library", onBack)
        Column(
            Modifier.weight(1f).padding(horizontal = 18.dp).height(56.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFFF5F5F5)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(asset.takenAt)), color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        if (onPlay != null) ViewerCircleButton(Icons.Outlined.PlayArrow, "Play live photo", onPlay) else Spacer(Modifier.size(8.dp))
        ViewerCircleButton(Icons.Outlined.DeleteOutline, "Delete photo", onDelete)
    }
}

@Composable
private fun ViewerBottomBar(
    photos: List<PhotoAsset>,
    selected: Int,
    filmstripState: androidx.compose.foundation.lazy.LazyListState,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Color(0xEDFFFFFF)).navigationBarsPadding()) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(68.dp)) {
            val thumbnailWidth = 32.dp
            LazyRow(
                state = filmstripState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = (maxWidth - thumbnailWidth) / 2),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(photos.size, key = { photos[it].uri }) { index ->
                    FilmstripThumbnail(photos[index], index == selected) { onSelect(index) }
                }
            }
        }
    }
}

@Composable
private fun ViewerCircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(50)).background(Color(0xFFF5F5F5)),
    ) { Icon(icon, description, tint = Color.Black, modifier = Modifier.size(26.dp)) }
}

private fun androidx.compose.foundation.lazy.LazyListState.centeredItemIndex(): Int? {
    val layout = layoutInfo
    val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
    return layout.visibleItemsInfo.minByOrNull { item -> abs((item.offset + item.size / 2) - viewportCenter) }?.index
}

private suspend fun centerFilmstripItem(state: androidx.compose.foundation.lazy.LazyListState, index: Int) {
    state.scrollToItem(index)
    val layout = state.layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
    state.animateScrollBy((item.offset + item.size / 2 - viewportCenter).toFloat())
}

private object LiveClipExtractor {
    private val ftyp = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())

    /** Xiaomi writes an ISO-BMFF stream directly after the JPEG bytes. */
    fun extract(context: android.content.Context, asset: PhotoAsset): Uri? {
        val output = File(context.cacheDir, "live-${asset.uri.lastPathSegment}.mp4")
        BufferedInputStream(context.contentResolver.openInputStream(asset.uri) ?: return null).use { input ->
            val window = ByteArray(8)
            var count = 0
            var matched = 0
            while (true) {
                val next = input.read()
                if (next == -1) return null
                window[count % window.size] = next.toByte()
                count++
                matched = if (next.toByte() == ftyp[matched]) matched + 1 else if (next.toByte() == ftyp[0]) 1 else 0
                if (matched == ftyp.size && count > window.size) {
                    FileOutputStream(output).use { stream ->
                        val start = count % window.size
                        repeat(window.size) { stream.write(window[(start + it) % window.size].toInt()) }
                        input.copyTo(stream)
                    }
                    return Uri.fromFile(output)
                }
            }
        }
    }
}

@Composable
private fun LiveClipPlayer(uri: Uri, onFinished: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context -> VideoView(context).apply {
                setVideoURI(uri)
                setOnPreparedListener { start() }
                setOnCompletionListener { onFinished() }
            } },
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(onClick = onFinished, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
            Icon(Icons.Outlined.Close, "Stop live photo", tint = Color.White)
        }
    }
}

@Composable
private fun FilmstripThumbnail(asset: PhotoAsset, selected: Boolean, onClick: () -> Unit) = AsyncImage(
    ImageRequest.Builder(LocalContext.current).data(asset.uri).size(160).build(), null,
    Modifier
        .size(if (selected) 44.dp else 32.dp)
        .clip(RoundedCornerShape(2.dp))
        .clickable(onClick = onClick),
    contentScale = ContentScale.Crop,
)
