package com.wtifs.photos

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentUris
import android.content.ContentResolver
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.material.icons.filled.Favorite
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhotosApp() }
    }
}

data class PhotoAsset(val uri: Uri, val takenAt: Long, val displayName: String, val favorite: Boolean = false, val modified: Long = 0, val videoRemoved: Boolean = false) {
    val mayContainLiveClip: Boolean get() = !videoRemoved && displayName.startsWith("MVIMG_", ignoreCase = true)
}

// A saved rotation must invalidate every size of this image, including the filmstrip.
private val imageRevisions = mutableStateMapOf<Uri, Int>()

@Composable
private fun photoRequest(asset: PhotoAsset, size: Int? = null): ImageRequest {
    val builder = ImageRequest.Builder(LocalContext.current).data(asset.uri)
        .memoryCacheKey("${asset.uri}:${asset.modified}:${imageRevisions[asset.uri] ?: 0}")
        .diskCachePolicy(coil.request.CachePolicy.DISABLED)
    if (size != null) builder.size(size)
    return builder.build()
}

class PhotosViewModel(application: Application) : AndroidViewModel(application) {
    var photos by mutableStateOf<List<PhotoAsset>>(emptyList())
        private set
    var trashedPhotos by mutableStateOf<List<PhotoAsset>>(emptyList())
        private set

    suspend fun loadPhotos() {
        photos = queryPhotos(MediaStore.MATCH_EXCLUDE)
    }

    suspend fun loadTrashedPhotos() {
        trashedPhotos = queryPhotos(MediaStore.MATCH_ONLY)
    }

    private suspend fun queryPhotos(matchTrashed: Int): List<PhotoAsset> {
        return withContext(Dispatchers.IO) {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.IS_FAVORITE,
                MediaStore.Images.Media.DATE_MODIFIED,
            )
            val queryArgs = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, matchTrashed)
                putString(
                    ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
                )
            }
            getApplication<Application>().contentResolver.query(collection, projection, queryArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                buildList {
                    val stillPhotos = getApplication<Application>().getSharedPreferences("still_photos", android.content.Context.MODE_PRIVATE)
                    while (cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                        val name = cursor.getString(nameColumn)
                        add(PhotoAsset(uri, cursor.getLong(dateColumn), name,
                            cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_FAVORITE)) == 1,
                            cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)),
                            stillPhotos.getBoolean("$uri|$name", false)))
                    }
                }
            } ?: emptyList()
        }
    }
}

@Composable
private fun PhotosApp(photosViewModel: PhotosViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { context.getSharedPreferences("photos_preferences", android.content.Context.MODE_PRIVATE) }
    val colors = MaterialTheme.colorScheme
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    var accessGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingPreviewDeleteIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var viewerGeneration by rememberSaveable { mutableIntStateOf(0) }
    var showingTrash by rememberSaveable { mutableStateOf(false) }
    var gridColumns by rememberSaveable { mutableIntStateOf(preferences.getInt("grid_columns", 3).coerceIn(3, 4)) }
    val libraryGridState = rememberLazyGridState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, accessGranted) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && accessGranted && pendingPreviewDeleteIndex == null) scope.launch { photosViewModel.loadPhotos() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { accessGranted = it }
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val deletedPreviewIndex = pendingPreviewDeleteIndex
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                try {
                    photosViewModel.loadPhotos()
                    if (deletedPreviewIndex != null) {
                        // The next photo takes the deleted photo's index; at the end use the previous one.
                        viewerIndex = if (photosViewModel.photos.isEmpty()) null
                            else deletedPreviewIndex.coerceAtMost(photosViewModel.photos.lastIndex)
                        viewerGeneration++
                    }
                    photosViewModel.loadTrashedPhotos()
                } finally { pendingPreviewDeleteIndex = null }
            }
        } else pendingPreviewDeleteIndex = null
    }
    val permanentDeleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                photosViewModel.loadPhotos()
                photosViewModel.loadTrashedPhotos()
            }
        }
    }

    LaunchedEffect(accessGranted) { if (accessGranted) photosViewModel.loadPhotos() }
    val requestTrash: (List<PhotoAsset>) -> Unit = { assets ->
        val request = MediaStore.createTrashRequest(context.contentResolver, assets.map { it.uri }, true)
        trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }
    val requestPermanentDelete: (List<PhotoAsset>) -> Unit = { assets ->
        val request = MediaStore.createDeleteRequest(context.contentResolver, assets.map { it.uri })
        permanentDeleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }
    val requestRestore: (List<PhotoAsset>) -> Unit = { assets ->
        val request = MediaStore.createTrashRequest(context.contentResolver, assets.map { it.uri }, false)
        trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(background = Color.White)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                !accessGranted -> PermissionView { permissionLauncher.launch(permission) }
                showingTrash -> TrashScreen(
                    photos = photosViewModel.trashedPhotos,
                    columns = gridColumns,
                    onBack = { showingTrash = false },
                    onRestore = requestRestore,
                    onPermanentDelete = requestPermanentDelete,
                )
                photosViewModel.photos.isEmpty() -> EmptyGalleryView()
                viewerIndex == null -> LibraryScreen(
                    photos = photosViewModel.photos,
                    gridState = libraryGridState,
                    columns = gridColumns,
                    onColumnsChange = { columns ->
                        gridColumns = columns
                        preferences.edit().putInt("grid_columns", columns).apply()
                    },
                    onOpen = { viewerIndex = it },
                    onDelete = requestTrash,
                    onOpenTrash = {
                        scope.launch { photosViewModel.loadTrashedPhotos() }
                        showingTrash = true
                    },
                )
                else -> key(viewerGeneration) { PhotoViewer(
                    photos = photosViewModel.photos,
                    initialIndex = viewerIndex!!.coerceIn(0, photosViewModel.photos.lastIndex),
                    onPageChanged = { viewerIndex = it },
                    onClose = { viewerIndex = null },
                    onDelete = { asset ->
                        pendingPreviewDeleteIndex = photosViewModel.photos.indexOfFirst { it.uri == asset.uri }.coerceAtLeast(0)
                        try { requestTrash(listOf(asset)) }
                        catch (failure: Exception) { pendingPreviewDeleteIndex = null; throw failure }
                    },
                    onRefresh = { scope.launch { photosViewModel.loadPhotos() } },
                ) }
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
private fun LibraryScreen(
    photos: List<PhotoAsset>,
    gridState: LazyGridState,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    onOpen: (Int) -> Unit,
    onDelete: (List<PhotoAsset>) -> Unit,
    onOpenTrash: () -> Unit,
) {
    var selectedUris by remember { mutableStateOf(setOf<Uri>()) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    val selecting = selectedUris.isNotEmpty()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selecting) "${selectedUris.size} Selected" else "Library", color = colors.onBackground, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (selecting) {
                IconButton(onClick = { selectedUris = photos.map { it.uri }.toSet() }) { Icon(Icons.Outlined.SelectAll, "Select all", tint = colors.onBackground) }
                IconButton(onClick = { onDelete(photos.filter { it.uri in selectedUris }); selectedUris = emptySet() }) { Icon(Icons.Outlined.DeleteOutline, "Move selected photos to trash", tint = Color(0xFFFF6B5E)) }
            } else {
                Box {
                    IconButton(onClick = { moreMenuExpanded = true }) { Icon(Icons.Outlined.MoreHoriz, "More library actions", tint = colors.onBackground) }
                    DropdownMenu(expanded = moreMenuExpanded, onDismissRequest = { moreMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("3 columns") },
                            trailingIcon = { if (columns == 3) Icon(Icons.Outlined.Check, null) },
                            onClick = { moreMenuExpanded = false; onColumnsChange(3) },
                        )
                        DropdownMenuItem(
                            text = { Text("4 columns") },
                            trailingIcon = { if (columns == 4) Icon(Icons.Outlined.Check, null) },
                            onClick = { moreMenuExpanded = false; onColumnsChange(4) },
                        )
                        DropdownMenuItem(
                            text = { Text("Trash") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                            onClick = { moreMenuExpanded = false; onOpenTrash() },
                        )
                    }
                }
            }
        }
        Text("${photos.size} Photos", color = colors.onBackground.copy(alpha = 0.58f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(columns), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                items(photos, key = { it.uri }) { asset ->
                    val selected = asset.uri in selectedUris
                    Box(
                        Modifier.padding(1.dp).fillMaxWidth()
                            .then(if (columns == 4) Modifier.aspectRatio(1f) else Modifier.height(132.dp))
                            .pointerInput(selecting) {
                                detectTapGestures(
                                    onTap = { if (selecting) selectedUris = selectedUris.toggle(asset.uri) else onOpen(photos.indexOf(asset)) },
                                    onLongPress = { selectedUris = selectedUris.toggle(asset.uri) },
                                )
                            },
                    ) {
                        AsyncImage(photoRequest(asset, 400), "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (selected) Box(Modifier.fillMaxSize().background(Color(0x550A84FF)))
                        val badgeSize = if (columns == 3) 18.dp else 15.dp
                        if (asset.favorite) Icon(Icons.Filled.Favorite, "Favorite", tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(badgeSize))
                        if (asset.mayContainLiveClip) LivePhotoBadge(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(badgeSize))
                        if (selecting) SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(8.dp))
                    }
                }
            }
            GalleryScrollIndicator(gridState, photos.size)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashScreen(
    photos: List<PhotoAsset>,
    columns: Int,
    onBack: () -> Unit,
    onRestore: (List<PhotoAsset>) -> Unit,
    onPermanentDelete: (List<PhotoAsset>) -> Unit,
) {
    val gridState = rememberLazyGridState()
    var selectedUris by remember { mutableStateOf(setOf<Uri>()) }
    val selecting = selectedUris.isNotEmpty()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back to library", tint = colors.onBackground) }
            Text(if (selecting) "${selectedUris.size} Selected" else "Trash", color = colors.onBackground, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (selecting) {
                IconButton(onClick = { onRestore(photos.filter { it.uri in selectedUris }); selectedUris = emptySet() }) {
                    Icon(Icons.Outlined.Restore, "Restore selected photos", tint = colors.onBackground)
                }
                IconButton(onClick = { onPermanentDelete(photos.filter { it.uri in selectedUris }); selectedUris = emptySet() }) {
                    Icon(Icons.Outlined.DeleteOutline, "Permanently delete selected photos", tint = Color(0xFFFF6B5E))
                }
            }
        }
        Text("${photos.size} Photos", color = colors.onBackground.copy(alpha = 0.58f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (photos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Trash is empty", color = Color(0xFFB8B8B8), fontSize = 17.sp) }
        } else {
            Box(Modifier.weight(1f)) {
                LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(columns), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                    items(photos, key = { it.uri }) { asset ->
                        val selected = asset.uri in selectedUris
                        Box(Modifier.padding(1.dp).fillMaxWidth().then(if (columns == 4) Modifier.aspectRatio(1f) else Modifier.height(132.dp)).pointerInput(selecting) {
                            detectTapGestures(
                                onTap = { if (selecting) selectedUris = selectedUris.toggle(asset.uri) },
                                onLongPress = { selectedUris = selectedUris.toggle(asset.uri) },
                            )
                        }) {
                            AsyncImage(photoRequest(asset, 400), "Trashed photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (selected) Box(Modifier.fillMaxSize().background(Color(0x55FF453A)))
                            if (selecting) SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(8.dp))
                        }
                    }
                }
                GalleryScrollIndicator(gridState, photos.size)
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

@Composable
private fun GalleryScrollIndicator(gridState: LazyGridState, itemCount: Int) {
    val firstIndex by remember { derivedStateOf { gridState.firstVisibleItemIndex } }
    val visibleCount by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.size } }
    if (itemCount <= visibleCount.coerceAtLeast(1)) return

    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var showThumb by remember { mutableStateOf(false) }
    LaunchedEffect(gridState.isScrollInProgress, dragging) {
        if (gridState.isScrollInProgress || dragging) {
            showThumb = true
        } else {
            delay(550)
            if (!gridState.isScrollInProgress && !dragging) showThumb = false
        }
    }
    AnimatedVisibility(
        visible = showThumb,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val visibleFraction = (visibleCount.toFloat() / itemCount).coerceIn(0.12f, 0.42f)
        val thumbHeight = (maxHeight * visibleFraction).coerceIn(24.dp, 56.dp)
        val travel = maxHeight - thumbHeight - 12.dp
        val scrollProgress = (firstIndex.toFloat() / (itemCount - visibleCount).coerceAtLeast(1)).coerceIn(0f, 1f)
        val progress = if (dragging) dragProgress else scrollProgress
        val travelPx = with(LocalDensity.current) { travel.toPx().coerceAtLeast(1f) }

        Box(
            Modifier.align(Alignment.TopEnd)
                .padding(end = 5.dp, top = 6.dp)
                .offset { IntOffset(0, (travelPx * progress).roundToInt()) }
                .width(14.dp)
                .height(thumbHeight)
                .clip(RoundedCornerShape(7.dp))
                .background(colors.onBackground.copy(alpha = 0.88f))
                .pointerInput(itemCount, visibleCount, travelPx) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            dragProgress = scrollProgress
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragProgress = (dragProgress + dragAmount.y / travelPx).coerceIn(0f, 1f)
                            val target = (dragProgress * (itemCount - 1)).roundToInt()
                            scope.launch { gridState.scrollToItem(target) }
                        },
                    )
                },
        ) {
            Box(Modifier.align(Alignment.Center).width(6.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(colors.background.copy(alpha = 0.7f)))
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoViewer(photos: List<PhotoAsset>, initialIndex: Int, onPageChanged: (Int) -> Unit, onClose: () -> Unit, onDelete: (PhotoAsset) -> Unit, onRefresh: () -> Unit) {
    var isDetail by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var metadata by remember { mutableStateOf(PhotoMetadata()) }
    var showInfo by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var pendingRotation by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVideoRemoval by rememberSaveable { mutableStateOf(false) }
    var locationRevision by remember { mutableIntStateOf(0) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { locationRevision++ }
    val favoriteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) onRefresh()
    }
    val rotateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val asset = photos.firstOrNull { it.uri.toString() == pendingRotation }
        val removeVideo = pendingVideoRemoval
        pendingRotation = null
        pendingVideoRemoval = false
        if (result.resultCode == Activity.RESULT_OK && asset != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    if (removeVideo) removeLiveVideo(context, asset) else rotatePhotoLeft(context, asset)
                }
                imageRevisions[asset.uri] = (imageRevisions[asset.uri] ?: 0) + 1
                revision++
                onRefresh()
            } catch (e: Exception) { error = e.message ?: "Could not save this image." }
            finally { busy = false }
        }
    }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = initialIndex, pageCount = { photos.size })
    val filmstripState = rememberLazyListState()
    var playingAsset by remember { mutableStateOf<PhotoAsset?>(null) }
    var clipUri by remember { mutableStateOf<Uri?>(null) }
    var centeringFilmstrip by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    BackHandler {
        if (isDetail) {
            isDetail = false
            zoom = 1f
            pan = Offset.Zero
        } else {
            onClose()
        }
    }
    val selected = pagerState.currentPage.coerceIn(0, photos.lastIndex)
    LaunchedEffect(photos[selected], revision, locationRevision) {
        metadata = PhotoMetadata()
        metadata = withContext(Dispatchers.IO) { readPhotoMetadata(context, photos[selected]) }
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED)
            locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }
    LaunchedEffect(selected) {
        onPageChanged(selected)
        zoom = 1f
        pan = Offset.Zero
        centeringFilmstrip = true
        try { centerFilmstripItem(filmstripState, selected) } finally { centeringFilmstrip = false }
    }
    LaunchedEffect(filmstripState) {
        snapshotFlow { if (!isDetail && filmstripState.isScrollInProgress) filmstripState.centeredItemIndex() else null }
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
                onExitDetail = { isDetail = false; zoom = 1f; pan = Offset.Zero },
                onDoubleTap = { zoom = if (zoom > 1f) 1f else 4f; pan = Offset.Zero },
                onSwitchPhoto = { direction ->
                    val target = (selected + direction).coerceIn(0, photos.lastIndex)
                    if (target != selected) scope.launch { pagerState.scrollToPage(target) }
                },
            )
        } else {
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                var dismissDistance by remember(photos[page].uri) { mutableFloatStateOf(0f) }
                AsyncImage(
                    model = photoRequest(photos[page]),
                    contentDescription = "Photo ${page + 1}",
                    modifier = Modifier.fillMaxSize().pointerInput(page) {
                        detectTapGestures(
                            onTap = { isDetail = true },
                            onDoubleTap = { zoom = 4f; pan = Offset.Zero; isDetail = true },
                            onLongPress = { if (photos[page].mayContainLiveClip) playingAsset = photos[page] },
                        )
                    }.pointerInput(photos[page].uri) {
                        val dismissThreshold = 24.dp.toPx()
                        detectVerticalDragGestures(
                            onDragStart = { dismissDistance = 0f },
                            onDragEnd = {
                                if (abs(dismissDistance) >= dismissThreshold) onClose()
                                dismissDistance = 0f
                            },
                            onDragCancel = { dismissDistance = 0f },
                            onVerticalDrag = { _, amount ->
                                dismissDistance += amount
                            },
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
                    location = metadata.location,
                    onBack = onClose,
                    onPlay = if (photos[selected].mayContainLiveClip) ({ playingAsset = photos[selected] }) else null,
                    onRotate = {
                        pendingVideoRemoval = false
                        pendingRotation = photos[selected].uri.toString()
                        runCatching { rotateLauncher.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(context.contentResolver, listOf(photos[selected].uri)).intentSender).build()) }
                            .onFailure { error = it.message; pendingRotation = null }
                    },
                    onRemoveVideo = {
                        pendingVideoRemoval = true
                        pendingRotation = photos[selected].uri.toString()
                        runCatching { rotateLauncher.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(context.contentResolver, listOf(photos[selected].uri)).intentSender).build()) }
                            .onFailure { error = it.message; pendingRotation = null; pendingVideoRemoval = false }
                    },
                    busy = busy,
                )
                Box(Modifier.align(Alignment.BottomCenter)) {
                    ViewerBottomBar(
                        photos = photos,
                        selected = selected,
                        filmstripState = filmstripState,
                        onSelect = { pagerState.requestScrollToPage(it) },
                        onFavorite = {
                            runCatching {
                                favoriteLauncher.launch(IntentSenderRequest.Builder(MediaStore.createFavoriteRequest(context.contentResolver, listOf(photos[selected].uri), !photos[selected].favorite).intentSender).build())
                            }.onFailure { error = it.message }
                        },
                        onInfo = { showInfo = true },
                        onDelete = { onDelete(photos[selected]) },
                    )
                }
            }
        }
        clipUri?.let { uri -> LiveClipPlayer(uri) { playingAsset = null; clipUri = null } }
        if (busy) Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("Photo information") }, text = { Text(metadata.details) }, confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Done") } })
        error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Unable to save") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DetailPhoto(asset: PhotoAsset, zoom: Float, pan: Offset, onTransform: (Float, Offset) -> Unit, onLongPress: () -> Unit, onExitDetail: () -> Unit, onDoubleTap: () -> Unit, onSwitchPhoto: (Int) -> Unit) {
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentSwitchPhoto by rememberUpdatedState(onSwitchPhoto)
    val currentZoom by rememberUpdatedState(zoom)
    val currentTransform by rememberUpdatedState(onTransform)
    AsyncImage(
        model = photoRequest(asset),
        contentDescription = "Photo detail",
        modifier = Modifier.fillMaxSize()
            .pointerInput(asset.uri) {
                val switchThreshold = 48.dp.toPx()
                // One recognizer owns swipes and pinches, so a second finger can always start zooming.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val startedZoomed = currentZoom > 1f
                    var multipleFingers = false
                    var distance = Offset.Zero
                    var totalZoom = 1f
                    var dragging = false
                    var cancelled = false
                    do {
                        val event = awaitPointerEvent()
                        multipleFingers = multipleFingers || event.changes.count { it.pressed || it.previousPressed } > 1
                        cancelled = event.changes.any { it.isConsumed }
                        if (!cancelled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            distance += panChange
                            totalZoom *= zoomChange
                            if (!dragging) {
                                val zoomMotion = abs(1f - totalZoom) * event.calculateCentroidSize(useCurrent = false)
                                dragging = distance.getDistance() > viewConfiguration.touchSlop || zoomMotion > viewConfiguration.touchSlop
                            }
                            if (dragging) {
                                if (multipleFingers || startedZoomed) currentTransform(zoomChange, panChange)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (!cancelled && event.changes.any { it.pressed })
                    if (!cancelled && !multipleFingers && !startedZoomed &&
                        abs(distance.x) >= switchThreshold && abs(distance.x) > abs(distance.y)) {
                        currentSwitchPhoto(if (distance.x < 0f) 1 else -1)
                    }
                }
            }
            .pointerInput(asset.uri) {
                detectTapGestures(onTap = { onExitDetail() }, onDoubleTap = { currentDoubleTap() }, onLongPress = { onLongPress() })
            }
            // Keep gesture distances in screen pixels rather than scaled image coordinates.
            .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun ViewerHeader(asset: PhotoAsset, location: String, onBack: () -> Unit, onPlay: (() -> Unit)?, onRotate: () -> Unit, onRemoveVideo: () -> Unit, busy: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(96.dp).background(Color(0xEFFFFFFF)).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewerCircleButton(Icons.Outlined.ArrowBack, "Back to library", onBack)
        Column(
            Modifier.weight(1f).padding(horizontal = 18.dp).height(56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (location.isNotBlank()) Text(location, color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(asset.takenAt)), color = Color.Black, fontSize = 11.sp, maxLines = 1)
        }
        if (onPlay != null) ViewerCircleButton(Icons.Outlined.PlayArrow, "Play live photo", onPlay) else Spacer(Modifier.size(8.dp))
        Box {
            ViewerCircleButton(Icons.Outlined.MoreHoriz, "More photo actions") { menuOpen = true }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Color.White,
                tonalElevation = 0.dp,
            ) {
                IconButton(
                    onClick = { menuOpen = false; onRotate() },
                    enabled = !busy,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    // RotateLeft has more internal padding; compensate within the same icon slot.
                    Icon(Icons.Outlined.RotateLeft, "Rotate left 90°", tint = if (busy) Color.Gray else Color.Black,
                        modifier = Modifier.size(24.dp).graphicsLayer(scaleX = 1.15f, scaleY = 1.15f))
                }
                if (asset.mayContainLiveClip) IconButton(
                    onClick = { menuOpen = false; onRemoveVideo() },
                    enabled = !busy,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Icon(Icons.Outlined.MotionPhotosOff, "Remove live video and overwrite JPEG", tint = if (busy) Color.Gray else Color.Black,
                        modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ViewerBottomBar(
    photos: List<PhotoAsset>,
    selected: Int,
    filmstripState: androidx.compose.foundation.lazy.LazyListState,
    onSelect: (Int) -> Unit,
    onFavorite: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
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
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 28.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onFavorite) { Icon(if (photos[selected].favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Toggle favorite", tint = if (photos[selected].favorite) Color.Red else Color.Black, modifier = Modifier.size(28.dp)) }
            IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, "EXIF information", tint = Color.Black, modifier = Modifier.size(26.dp)) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "Move photo to trash", tint = Color.Black, modifier = Modifier.size(26.dp)) }
        }
    }
}

@Composable
private fun ViewerCircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
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
private fun LivePhotoBadge(modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = "Live photo" }) {
        val diameter = size.minDimension
        val stroke = diameter * 0.055f
        val tint = Color.White.copy(alpha = 0.8f)
        drawCircle(tint, radius = diameter * 0.12f)
        drawCircle(tint, radius = diameter * 0.28f, style = Stroke(stroke))
        val radius = diameter * 0.44f
        repeat(18) { segment ->
            drawArc(
                color = tint,
                startAngle = segment * 20f,
                sweepAngle = 7f,
                useCenter = false,
                topLeft = center - Offset(radius, radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun FilmstripThumbnail(asset: PhotoAsset, selected: Boolean, onClick: () -> Unit) = AsyncImage(
    photoRequest(asset, 160), null,
    Modifier
        .size(if (selected) 44.dp else 32.dp)
        .clip(RoundedCornerShape(2.dp))
        .clickable(onClick = onClick),
    contentScale = ContentScale.Crop,
)
