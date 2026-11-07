package me.rerere.rikkahub.ui.pages.chat

import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.BACKGROUND_SCALE_FIT
import me.rerere.rikkahub.data.model.BACKGROUND_SCALE_STRETCH

/** 背景既可以是图片也可以是视频，按扩展名分流。 */
private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "avi", "ts")

/**
 * 这个 URI 是不是视频。
 *
 * 只看扩展名就够了：相册选中的文件会先被复制进聊天文件目录，扩展名保留；
 * 手填网络地址时用户也会带 .mp4 之类的后缀。content:// 走不到这里。
 */
internal fun isVideoBackground(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.substringAfterLast('.', "") in VIDEO_EXTENSIONS
}

@Composable
fun AssistantBackground(setting: Settings, modifier: Modifier) {
    val assistant = setting.getCurrentAssistant()
    if (assistant.useGradientBackground) {
        MeshGradientBackground(modifier = modifier)
        return
    }
    val background = assistant.background ?: return
    val backgroundColor = MaterialTheme.colorScheme.background
    val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
    val scaleMode = assistant.backgroundScale
    Box(modifier = modifier) {
        if (isVideoBackground(background)) {
            VideoBackground(
                url = background,
                opacity = backgroundOpacity,
                scaleMode = scaleMode,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AsyncImage(
                model = background,
                contentDescription = null,
                contentScale = when (scaleMode) {
                    BACKGROUND_SCALE_FIT -> ContentScale.Fit
                    BACKGROUND_SCALE_STRETCH -> ContentScale.FillBounds
                    else -> ContentScale.Crop
                },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(backgroundOpacity)
            )
        }

        // 全屏渐变遮罩
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            backgroundColor.copy(alpha = 0.2f),
                            backgroundColor.copy(alpha = 0.5f)
                        )
                    )
                )
        )
    }
}

/**
 * 视频背景。
 *
 * 用 TextureView 而不是 PlayerView：PlayerView 默认走 SurfaceView，而 SurfaceView 不参与
 * Compose 的 alpha 合成——无论怎么设透明度，画面都是全不透明的，助手设置里的
 * 「背景不透明度」会直接失效。PlayerView 的 surface_type 又只能在 XML 里指定，
 * 代码里 new 出来的实例改不了。所以这里自己搭 AspectRatioFrameLayout + TextureView。
 *
 * RESIZE_MODE_ZOOM 相当于图片那边的 ContentScale.Crop：铺满、不变形、超出的裁掉。
 */
@Composable
private fun VideoBackground(
    url: String,
    opacity: Float,
    scaleMode: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 变量名不能叫 resizeMode：下面 apply { } 块里的裸 resizeMode 会优先解析成
    // AspectRatioFrameLayout 自己的属性，赋值就成了 this.x = this.x，缩放永远不生效。
    val frameResizeMode = when (scaleMode) {
        BACKGROUND_SCALE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        BACKGROUND_SCALE_STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            // 背景不该出声，音量固定 0，也不提供开关
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // 退到后台就停，回来再播。背景视频一直跑是在白烧电。
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.play()
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier.alpha(opacity),
        factory = { ctx ->
            AspectRatioFrameLayout(ctx).apply {
                this.resizeMode = frameResizeMode
                val textureView = TextureView(ctx)
                addView(
                    textureView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                player.setVideoTextureView(textureView)
                player.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        val width = videoSize.width * videoSize.pixelWidthHeightRatio
                        if (width > 0f && videoSize.height > 0) {
                            setAspectRatio(width / videoSize.height)
                        }
                    }
                })
            }
        },
        // factory 只在首次组合时跑一次，之后换缩放方式就靠 update 把新值推进去。
        update = { view -> view.resizeMode = frameResizeMode },
    )
}
