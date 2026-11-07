package io.legado.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.alpha
import androidx.core.view.forEach
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import com.google.android.material.shape.MaterialShapeDrawable
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.MainTopBarImageManager
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.getToolbarTextColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.activity
import io.legado.app.utils.applyTint
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import splitties.views.bottomPadding
import splitties.views.topPadding

@Suppress("unused", "MemberVisibilityCanBePrivate")
class TitleBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppBarLayout(context, attrs) {

    val toolbar: Toolbar
    val menu: Menu
        get() = toolbar.menu

    var title: CharSequence?
        get() = toolbar.title
        set(title) {
            if (toolbar.title != title) {
                toolbar.title = title
            }
        }

    var subtitle: CharSequence?
        get() = toolbar.subtitle
        set(subtitle) {
            if (toolbar.subtitle != subtitle) {
                toolbar.subtitle = subtitle
            }
        }

    private val displayHomeAsUp: Boolean
    private val navigationDescription: CharSequence
    private val navigationIconTint: ColorStateList?
    private val navigationIconTintMode: Int
    private val fitStatusBar: Boolean
    private val fitNavigationBar: Boolean
    private val mainTopBar: Boolean
    private val forceImmersive: Boolean
    private val attachToActivity: Boolean
    private val opaque: Boolean
    private val automaticForeground: Boolean
    private val titleTextColorFromAttrs: Boolean
    private val subtitleTextColorFromAttrs: Boolean

    init {
        val a = context.obtainStyledAttributes(
            attrs, R.styleable.TitleBar,
            R.attr.titleBarStyle, 0
        )
        navigationIconTint = a.getColorStateList(R.styleable.TitleBar_navigationIconTint)
        navigationIconTintMode = a.getInt(R.styleable.TitleBar_navigationIconTintMode, 9)
        attachToActivity = a.getBoolean(R.styleable.TitleBar_attachToActivity, true)
        displayHomeAsUp = a.getBoolean(R.styleable.TitleBar_displayHomeAsUp, true)
        fitStatusBar = a.getBoolean(R.styleable.TitleBar_fitStatusBar, true)
        fitNavigationBar = a.getBoolean(R.styleable.TitleBar_fitNavigationBar, false)
        mainTopBar = a.getBoolean(R.styleable.TitleBar_mainTopBar, false)
        forceImmersive = a.getBoolean(R.styleable.TitleBar_forceImmersive, false)
        opaque = a.getBoolean(R.styleable.TitleBar_opaque, false)
        val themeMode = a.getInt(R.styleable.TitleBar_themeMode, 0)
        automaticForeground = themeMode == 0 && !opaque

        val navigationIcon = a.getDrawable(R.styleable.TitleBar_navigationIcon)
        navigationDescription =
            a.getText(R.styleable.TitleBar_navigationContentDescription)
                ?: context.getText(R.string.back)
        val titleText = a.getString(R.styleable.TitleBar_title)
        val subtitleText = a.getString(R.styleable.TitleBar_subtitle)
        titleTextColorFromAttrs = a.hasValue(R.styleable.TitleBar_titleTextColor)
        subtitleTextColorFromAttrs = a.hasValue(R.styleable.TitleBar_subtitleTextColor)

        when (themeMode) {
            1 -> inflate(context, R.layout.view_title_bar_dark, this)
            else -> inflate(context, R.layout.view_title_bar, this)
        }
        toolbar = findViewById(R.id.toolbar)

        toolbar.apply {
            navigationIcon?.let {
                this.navigationIcon = it
                this.navigationContentDescription = navigationDescription
            }

            if (a.hasValue(R.styleable.TitleBar_titleTextAppearance)) {
                this.setTitleTextAppearance(
                    context,
                    a.getResourceId(R.styleable.TitleBar_titleTextAppearance, 0)
                )
            }

            if (titleTextColorFromAttrs) {
                this.setTitleTextColor(a.getColor(R.styleable.TitleBar_titleTextColor, -0x1))
            }

            if (a.hasValue(R.styleable.TitleBar_subtitleTextAppearance)) {
                this.setSubtitleTextAppearance(
                    context,
                    a.getResourceId(R.styleable.TitleBar_subtitleTextAppearance, 0)
                )
            }

            if (subtitleTextColorFromAttrs) {
                this.setSubtitleTextColor(a.getColor(R.styleable.TitleBar_subtitleTextColor, -0x1))
            }


            if (a.hasValue(R.styleable.TitleBar_contentInsetLeft)
                || a.hasValue(R.styleable.TitleBar_contentInsetRight)
            ) {
                this.setContentInsetsAbsolute(
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetLeft, 0),
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetRight, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetStart)
                || a.hasValue(R.styleable.TitleBar_contentInsetEnd)
            ) {
                this.setContentInsetsRelative(
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetStart, 0),
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetEnd, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetStartWithNavigation)) {
                this.contentInsetStartWithNavigation = a.getDimensionPixelOffset(
                    R.styleable.TitleBar_contentInsetStartWithNavigation, 0
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetEndWithActions)) {
                this.contentInsetEndWithActions = a.getDimensionPixelOffset(
                    R.styleable.TitleBar_contentInsetEndWithActions, 0
                )
            }

            if (!titleText.isNullOrBlank()) {
                this.title = titleText
            }

            if (!subtitleText.isNullOrBlank()) {
                this.subtitle = subtitleText
            }

            if (a.hasValue(R.styleable.TitleBar_contentLayout)) {
                inflate(context, a.getResourceId(R.styleable.TitleBar_contentLayout, 0), this)
            }
        }

        if (!isInEditMode) {
//            if (fitStatusBar) {
//                setPadding(paddingLeft, context.statusBarHeight, paddingRight, paddingBottom)
//            }
//
//            if (fitNavigationBar) {
//                setPadding(paddingLeft, paddingTop, paddingRight, context.navigationBarHeight)
//            }

            if (fitStatusBar || fitNavigationBar) {
                setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                    if (fitStatusBar) {
                        topPadding = insets.top
                    }
                    if (fitNavigationBar) {
                        bottomPadding = insets.bottom
                    }
                    windowInsets
                }
            }

            if (AppConfig.isEInkMode) {
                setBackgroundResource(R.drawable.bg_eink_border_bottom)
            } else if (!opaque) {
                applyTopBarStyle()
            } else {
                setBackgroundColor(context.primaryColor)
                elevation = context.elevation
            }

            stateListAnimator = null
        }
        a.recycle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachToActivity()
        if (automaticForeground) {
            post { applyForegroundColor() }
        }
    }

    /** 是否是主界面顶部操作栏（主界面刷新时用来筛选）。 */
    val isMainTopBar: Boolean
        get() = mainTopBar

    val isOpaqueBar: Boolean
        get() = opaque

    val isForceImmersive: Boolean
        get() = forceImmersive

    /**
     * 当前是否显示顶部图片。
     * 沉浸式优先：打开顶部沉浸或页面强制沉浸时操作栏保持透明，图片暂时不显示。
     */
    val showsTopBarImage: Boolean
        get() = !opaque && !forceImmersive && !AppConfig.isEInkMode &&
            !AppConfig.mainTopBarImmersive && MainTopBarImageManager.hasImage()

    // AppBarLayout can wrap a transparent color in a shape whose drawable alpha is still 255.
    val usesTransparentForeground: Boolean
        get() = automaticForeground &&
            (forceImmersive || AppConfig.mainTopBarImmersive || showsTopBarImage) &&
            !AppConfig.isEInkMode && (background?.alpha == 0 ||
                (background as? MaterialShapeDrawable)?.fillColor
                    ?.getColorForState(drawableState, Color.TRANSPARENT)?.alpha == 0 ||
                showsTopBarImage)

    /**
     * 按当前日间/夜间主题重新应用顶部操作栏背景，所有非 opaque 页面共用同一张顶部图片，
     * 设置页改图或从设置页返回时调用。
     */
    fun applyTopBarStyle() {
        if (AppConfig.isEInkMode) {
            setBackgroundResource(R.drawable.bg_eink_border_bottom)
            return
        }
        if (forceImmersive) {
            // 书籍信息等页面要求始终沉浸，优先级高于 opaque 和图片
            setBackgroundColor(Color.TRANSPARENT)
            return
        }
        if (opaque) {
            setBackgroundColor(context.primaryColor)
            elevation = context.elevation
            return
        }
        if (AppConfig.mainTopBarImmersive) {
            // 沉浸式优先，打开后操作栏透明；图片在关闭沉浸后恢复
            setBackgroundColor(Color.TRANSPARENT)
            return
        }
        val image = MainTopBarImageManager.loadBitmap(context)
        if (image != null) {
            background = CenterCropDrawable(image)
            elevation = 0.1f
        } else {
            setBackgroundColor(context.primaryColor)
            elevation = context.elevation
        }
    }

    fun applyForegroundColor() {
        if (!usesTransparentForeground) return
        val hasImage = showsTopBarImage
        // 有顶部图片时固定白天黑色、夜间白色，不再按图片明暗切换
        val color = if (hasImage) {
            if (AppConfig.isNightTheme) Color.WHITE else Color.BLACK
        } else {
            context.getToolbarTextColor(true)
        }
        if (!titleTextColorFromAttrs) {
            setTitleTextColor(color)
        }
        if (!subtitleTextColorFromAttrs) {
            setSubTitleTextColor(color)
        }
        // 有图时给顶部操作栏所有文字和图标加阴影：白字黑阴影、黑字白阴影
        val density = context.resources.displayMetrics.density
        val shadowColor = when {
            !hasImage -> 0
            AppConfig.isNightTheme -> 0xCC000000.toInt()
            else -> 0xCCFFFFFF.toInt()
        }
        val shadowRadius = 2f * density
        val shadowDy = density
        // 菜单项和 Toolbar 自己持有的图标先包一层，菜单刷新后仍能保留阴影
        if (hasImage) {
            toolbar.navigationIcon?.let { icon ->
                if (icon !is ShadowDrawable) {
                    toolbar.navigationIcon = ShadowDrawable(icon, shadowColor, shadowRadius, shadowDy)
                }
            }
            toolbar.overflowIcon?.let { icon ->
                if (icon !is ShadowDrawable) {
                    toolbar.overflowIcon = ShadowDrawable(icon, shadowColor, shadowRadius, shadowDy)
                }
            }
            toolbar.menu.forEach { item ->
                val icon = item.icon
                if (icon != null && icon !is ShadowDrawable) {
                    item.icon = ShadowDrawable(icon, shadowColor, shadowRadius, shadowDy)
                }
            }
        } else {
            (toolbar.navigationIcon as? ShadowDrawable)?.let {
                toolbar.navigationIcon = it.source
            }
            (toolbar.overflowIcon as? ShadowDrawable)?.let {
                toolbar.overflowIcon = it.source
            }
            toolbar.menu.forEach { item ->
                (item.icon as? ShadowDrawable)?.let { item.icon = it.source }
            }
        }
        applyShadowToBar(hasImage, shadowColor, shadowRadius, shadowDy)
        val colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        toolbar.navigationIcon?.colorFilter = colorFilter
        toolbar.overflowIcon?.colorFilter = colorFilter
        toolbar.findViewById<SearchView>(R.id.search_view)?.applyTint(color)
        val tabUnselectedColor = context.getCompatColor(
            if (ColorUtils.isColorLight(context.backgroundColor)) {
                R.color.md_light_secondary
            } else {
                R.color.md_dark_secondary
            }
        )
        toolbar.findViewById<TabLayout>(R.id.tab_layout)
            ?.setTabTextColors(tabUnselectedColor, color)
        toolbar.menu.forEach { item ->
            (item.actionView as? SearchView)?.applyTint(color)
        }
        toolbar.children.filterIsInstance<ActionMenuView>().forEach { actions ->
            actions.children.filterIsInstance<TextView>().forEach { action ->
                action.setTextColor(color)
            }
        }
    }

    private fun applyShadowToBar(hasImage: Boolean, color: Int, radius: Float, dy: Float) {
        val shadowRadius = if (hasImage) radius else 0f
        val shadowDy = if (hasImage) dy else 0f
        fun visit(view: View) {
            when (view) {
                is TextView -> view.setShadowLayer(shadowRadius, 0f, shadowDy, color)
                is ImageView -> {
                    val drawable = view.drawable
                    if (hasImage) {
                        if (drawable != null && drawable !is ShadowDrawable) {
                            view.setImageDrawable(ShadowDrawable(drawable, color, radius, dy))
                        }
                    } else if (drawable is ShadowDrawable) {
                        view.setImageDrawable(drawable.source)
                    }
                }

                is ViewGroup -> {
                    for (i in 0 until view.childCount) {
                        visit(view.getChildAt(i))
                    }
                }
            }
        }
        visit(toolbar)
    }

    fun setNavigationOnClickListener(clickListener: ((View) -> Unit)) {
        toolbar.setNavigationOnClickListener(clickListener)
    }

    fun setTitle(titleId: Int) {
        toolbar.setTitle(titleId)
    }

    fun setSubTitle(subtitleId: Int) {
        toolbar.setSubtitle(subtitleId)
    }

    fun setTitleTextColor(@ColorInt color: Int) {
        toolbar.setTitleTextColor(color)
    }

    fun setTitleTextAppearance(@StyleRes resId: Int) {
        toolbar.setTitleTextAppearance(context, resId)
    }

    fun setSubTitleTextColor(@ColorInt color: Int) {
        toolbar.setSubtitleTextColor(color)
    }

    fun setSubTitleTextAppearance(@StyleRes resId: Int) {
        toolbar.setSubtitleTextAppearance(context, resId)
    }

    fun setTextColor(@ColorInt color: Int) {
        setTitleTextColor(color)
        setSubTitleTextColor(color)
    }

    fun setColorFilter(@ColorInt color: Int) {
        val colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        toolbar.children.firstOrNull { it is ImageView }?.background?.colorFilter = colorFilter
        toolbar.navigationIcon?.colorFilter = colorFilter
        toolbar.overflowIcon?.colorFilter = colorFilter
        toolbar.menu.children.forEach {
            it.icon?.colorFilter = colorFilter
        }
    }

    override fun setBackgroundColor(color: Int) {
        if (color.alpha < 255) {
            //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
            elevation = 0.1f
        }
        super.setBackgroundColor(color)
    }

    override fun setBackground(background: Drawable?) {
        if (background is ColorDrawable) {
            if (background.alpha < 255) {
                //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
                elevation = 0.1f
            }
        }
        super.setBackground(background)
    }

    fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, fullScreen: Boolean) {
//        if (fitStatusBar) {
//            val topPadding = if (!isInMultiWindowMode && fullScreen) context.statusBarHeight else 0
//            setPadding(paddingLeft, topPadding, paddingRight, paddingBottom)
//        }
    }

    private fun attachToActivity() {
        if (attachToActivity) {
            activity?.let {
                it.setSupportActionBar(toolbar)
                it.supportActionBar?.apply {
                    setDisplayHomeAsUpEnabled(displayHomeAsUp)
                    if (displayHomeAsUp) {
                        setHomeActionContentDescription(navigationDescription)
                    }
                }
            }
        }
    }

}
