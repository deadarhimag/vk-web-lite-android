package com.example.vkweb.ui

import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.example.vkweb.R
import com.example.vkweb.config.HiddenSection
import com.example.vkweb.config.Tab
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView

/**
 * Меню разделов. Вид — [NavStyle] (4.50):
 *  - у Android жест «Назад» от края экрана — нижняя панель: четыре раздела и «Ещё» (боковое меню целиком — профиль,
 *    остальные разделы, настройки). Свайп от края остаётся системе: меню им не открывается;
 *  - жеста от края нет (три кнопки, жест выключен) — боковое меню свайпом слева и всегда кнопкой ☰;
 *  - закрепление на широких экранах (≥ [R.integer.permanent_nav_min_width_dp]) — меню всегда видно слева.
 * Пункты строятся из [Tab], поэтому список разделов задаётся в одном месте.
 */
class NavigationController(
    private val drawer: DrawerLayout,
    private val navView: NavigationView,
    private val permanentContainer: FrameLayout,
    private val permanentDivider: View,
    private val toolbar: MaterialToolbar,
    private val listener: Listener,
    /** Нижняя панель разделов (4.50); null — у экрана её нет. */
    private val bottomBar: BottomNavigationView? = null,
    /** Полоса значков слева на средней ширине (4.58); null — у экрана её нет. */
    private val rail: com.google.android.material.navigationrail.NavigationRailView? = null,
) {
    /** Нижняя панель и полоса: одни пункты, отметки и число непрочитанных; видна одна из них (или ни одной). */
    private val bars: List<com.google.android.material.navigation.NavigationBarView> = listOfNotNull(bottomBar, rail)

    interface Listener {
        fun onSectionSelected(tab: Tab)
        fun onExtraSectionSelected(section: HiddenSection)
        fun onSettingsSelected()

        /** Нажата шапка меню со своей страницей (3.94). */
        fun onProfileSelected(profile: com.example.vkweb.account.SelfProfile) {}

        /** На нижней панели снова нажат открытый раздел (4.50): к его началу. По умолчанию — как выбор раздела. */
        fun onSectionReselected(tab: Tab) = onSectionSelected(tab)
    }

    /**
     * Шапка меню (3.94): своя страница — круглое фото (пока не загрузилось — инициалы) и имя, нажатие — на неё; нет
     * данных (не входили, вышли) — значок и название приложения.
     */
    fun showProfile(profile: com.example.vkweb.account.SelfProfile?) {
        if (navView.headerCount == 0) return
        val header = navView.getHeaderView(0)
        val avatar = header.findViewById<android.widget.ImageView>(R.id.nav_header_avatar)
        val title = header.findViewById<android.widget.TextView>(R.id.nav_header_title)
        val subtitle = header.findViewById<android.widget.TextView>(R.id.nav_header_subtitle)
        if (profile == null) {
            avatar.clipToOutline = false
            avatar.setImageResource(R.mipmap.ic_launcher)
            title.setText(R.string.app_name)
            subtitle.setText(R.string.app_tagline)
            header.setOnClickListener(null)
            header.isClickable = false
            return
        }
        avatar.outlineProvider = CircleOutline
        avatar.clipToOutline = true
        avatar.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        val px = (AVATAR_DP * density).toInt()
        com.example.vkweb.offline.ImageCache.get(header.context).load(avatar, profile.photo.ifEmpty { null }, px) {
            com.example.vkweb.messages.AvatarBadge(profile.name)
        }
        title.text = profile.name
        subtitle.setText(R.string.nav_header_open_profile)
        header.setOnClickListener {
            closeIfModal()
            listener.onProfileSelected(profile)
        }
    }

    private object CircleOutline : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
    }

    private val resources = drawer.resources
    private val permanentMinWidthDp = resources.getInteger(R.integer.permanent_nav_min_width_dp)
    private val density = resources.displayMetrics.density

    private var screenWidthDp = resources.configuration.screenWidthDp
    private var pinOnWideScreens = false
    private var styleChoice = NavStyle.Choice.DEFAULT
    private var swipeEnabled = true
    private var isPermanent = false

    private var styleLogged = false

    /** Вид меню сейчас (4.50). */
    var style: NavStyle = NavStyle.DRAWER
        private set

    /** Есть жест «Назад» от края экрана ([NavStyle.edgeBackGesture]); пересчитывается при перестройке экрана. */
    private var edgeBackGesture = detectEdgeBackGesture()

    /** Экран сам прячет нижнюю панель (открытая переписка с полем ввода). */
    var bottomBarSuppressed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateBottomBar(requestInsets = true)
        }

    private var imeVisible = false
    private var unreadDialogs = 0
    private var bottomTabs: List<Tab> = emptyList()

    // Объявлены до init: он строит панели (setupBottomBar).
    /** Короткие подписи по умолчанию; настройки могут включить компактный вид без них. */
    private var labels = true

    /** Высота панели с подписями (из темы Material) — чтобы вернуть её, когда подписи включат. */
    private val labeledMinHeight = HashMap<com.google.android.material.navigation.NavigationBarView, Int>()

    /**
     * Для [SystemBars.bottom]: пока панель видна, содержимому не нужен отступ от панели навигации; клавиатура её прячет.
     * Вызывается во время раздачи отступов — поэтому здесь отступы заново не запрашиваются.
     */
    val bottomInsets = object : SystemBars.BottomBar {
        // Без учёта клавиатуры: её SystemBars видит в тех же отступах (порядок раздачи по экранам не важен).
        override val isShown: Boolean get() = bottomBar != null && style == NavStyle.BOTTOM && !bottomBarSuppressed
        override fun onImeChanged(visible: Boolean) {
            if (visible == imeVisible) return
            imeVisible = visible
            updateBottomBar(requestInsets = false)
        }
    }

    /**
     * Кнопка ☰ и при включённом жесте — пока меню ни разу не открывали (4.1). Меню открывается свайпом от края, но
     * этого не видно: новый пользователь не находил остальные разделы. После первого открытия кнопка уходит.
     */
    private var menuHint = false

    /** Меню открыли впервые (любым способом): сохранить, чтобы кнопка ☰ больше не показывалась. */
    var onMenuDiscovered: (() -> Unit)? = null

    /** Меню закреплено слева (занимает место экрана). */
    val isPinned: Boolean get() = isPermanent

    // Объявлены до init: Kotlin инициализирует поля и init сверху вниз, а init уже строит меню.
    private var hiddenTabs: Set<Tab> = emptySet()
    private var extraSections: List<HiddenSection> = emptyList()

    /** Отмеченный раздел: меню перестраивается при смене настроек, и отметку нужно вернуть. */
    private var highlighted: Tab? = null
    private var highlightedSection: HiddenSection? = null

    /**
     * Место слева на панели. Обычно пусто (меню открывается жестом); экран может временно забрать его
     * себе (например, «назад» в открытой переписке), выставив false, и вернуть, выставив true.
     * Объявлено до init по той же причине, что и поля выше.
     */
    var controlsToolbar: Boolean = true
        set(value) {
            field = value
            attachToolbar()
        }

    init {
        buildMenu()
        navView.setNavigationItemSelectedListener { item ->
            when (item.groupId) {
                GROUP_SECTIONS -> listener.onSectionSelected(Tab.entries[item.itemId - ITEM_ID_BASE])
                GROUP_EXTRA -> listener.onExtraSectionSelected(HiddenSection.entries[item.itemId - EXTRA_ID_BASE])
                GROUP_APP -> listener.onSettingsSelected()
            }
            closeIfModal()
            true
        }
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                if (menuHint) {
                    menuHint = false
                    attachToolbar()
                    // Открыто впервые (4.11): по журналу видно, нашли ли меню и сколько на это ушло.
                    com.example.vkweb.diag.AppLog.i(com.example.vkweb.diag.LogTag.APP, "меню открыто впервые — кнопка ☰ больше не показывается")
                    onMenuDiscovered?.invoke()
                }
                updateGestures()
            }
            override fun onDrawerClosed(drawerView: View) = updateGestures()
        })
        // Зона жеста зависит от высоты экрана. Вид меню — от жестов Android: зоны жестов окна известны только после
        // того, как окно показано, и меняются со сменой навигации в настройках Android.
        drawer.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val edge = detectEdgeBackGesture()
            if (edge != edgeBackGesture) {
                edgeBackGesture = edge
                // Не посреди раскладки: смена вида меняет видимость панели и состав экрана.
                drawer.post { applyMode() }
            } else if (bottom - top != oldBottom - oldTop) {
                updateGestures()
            }
        }
        bars.forEach(::setupBottomBar)
        applyMode()
    }

    private fun detectEdgeBackGesture(): Boolean {
        val gestures = androidx.core.view.ViewCompat.getRootWindowInsets(drawer)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemGestures())
        // Настройка Android — только пока зон жестов нет (окно ещё не показано): раскладка бывает часто.
        val mode = if (gestures != null) -1 else try {
            android.provider.Settings.Secure.getInt(drawer.context.contentResolver, "navigation_mode", -1)
        } catch (e: Exception) {
            -1
        }
        return NavStyle.edgeBackGesture(Build.VERSION.SDK_INT, gestures?.left, gestures?.right, mode)
    }

    // ------------------------------------------------------------ нижняя панель (4.50)

    private fun setupBottomBar(bar: com.google.android.material.navigation.NavigationBarView) {
        labeledMinHeight[bar] = bar.minimumHeight
        applyLabels(bar)
        bar.setOnItemSelectedListener { item ->
            if (item.itemId == ID_MORE) {
                // «Ещё» — боковое меню целиком; сам пункт не отмечается: отмечен раздел, который открыт.
                drawer.openDrawer(navView)
                false
            } else {
                listener.onSectionSelected(Tab.entries[item.itemId - ITEM_ID_BASE])
                true
            }
        }
        bar.setOnItemReselectedListener { item ->
            if (item.itemId == ID_MORE) drawer.openDrawer(navView)
            else listener.onSectionReselected(Tab.entries[item.itemId - ITEM_ID_BASE])
        }
        buildBottomMenu(bar)
    }

    private fun buildBottomMenu() = bars.forEach(::buildBottomMenu)

    private fun buildBottomMenu(bar: com.google.android.material.navigation.NavigationBarView) {
        bottomTabs = NavStyle.bottomSections(PREFERRED_BOTTOM, hiddenTabs)
        val menu = bar.menu
        menu.clear()
        bottomTabs.forEachIndexed { index, tab ->
            val label = when (tab) {
                Tab.MESSAGES -> R.string.nav_chats
                Tab.GROUPS -> R.string.nav_groups_short
                else -> tab.titleRes
            }
            menu.add(0, idOf(tab), index, label).setIcon(tab.iconRes)
        }
        menu.add(0, ID_MORE, bottomTabs.size, R.string.nav_more).setIcon(R.drawable.ic_menu)
        applyUnreadBadge(bar)
    }

    /**
     * Число непрочитанных диалогов на «Сообщениях» нижней панели (4.50): тех, о которых сообщают уведомления
     * ([com.example.vkweb.sync.DialogNotifications.badgeCount]).
     */
    fun setUnreadDialogs(count: Int) {
        if (count == unreadDialogs) return
        unreadDialogs = count
        applyUnreadBadge()
    }

    private fun applyUnreadBadge() = bars.forEach(::applyUnreadBadge)

    private fun applyUnreadBadge(bar: com.google.android.material.navigation.NavigationBarView) {
        val id = idOf(Tab.MESSAGES)
        if (Tab.MESSAGES !in bottomTabs || unreadDialogs <= 0) {
            bar.removeBadge(id)
            return
        }
        bar.getOrCreateBadge(id).apply {
            number = unreadDialogs
            // Цветом счётчиков списка диалогов, а не красным Material: одна палитра в приложении.
            backgroundColor = com.google.android.material.color.MaterialColors.getColor(bar, androidx.appcompat.R.attr.colorPrimary)
            badgeTextColor = com.google.android.material.color.MaterialColors.getColor(bar, com.google.android.material.R.attr.colorOnPrimary)
            isVisible = true
        }
    }

    /**
     * Панель видна: вид [NavStyle.BOTTOM], экран её не прячет, клавиатура закрыта. Полоса слева (4.58) — при виде
     * [NavStyle.RAIL] всегда: поле ввода и клавиатуру она не закрывает.
     */
    private fun updateBottomBar(requestInsets: Boolean) {
        rail?.visibility = if (style == NavStyle.RAIL) View.VISIBLE else View.GONE
        val bar = bottomBar ?: return
        val show = style == NavStyle.BOTTOM && !bottomBarSuppressed && !imeVisible
        val visibility = if (show) View.VISIBLE else View.GONE
        if (bar.visibility == visibility) return
        bar.visibility = visibility
        // Отступ содержимого от панели навигации зависит от того, видна ли панель (SystemBars.bottom).
        if (requestInsets) androidx.core.view.ViewCompat.requestApplyInsets(drawer)
    }

    /**
     * Кнопка ☰ — у бокового меню без закрепления всегда (4.50: раньше уходила после первого открытия, и меню
     * оставалось только свайпом). У нижней панели её нет: меню там — «Ещё».
     */
    private fun attachToolbar() {
        if (!controlsToolbar) return
        if (style != NavStyle.DRAWER) {
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
            return
        }
        toolbar.setNavigationIcon(R.drawable.ic_menu)
        toolbar.setNavigationContentDescription(R.string.open_menu)
        toolbar.setNavigationOnClickListener { if (!isPermanent) drawer.openDrawer(navView) }
    }

    /** Вызывать при смене размера окна (поворот, режим окон). */
    fun updateLayout(screenWidthDp: Int) {
        this.screenWidthDp = screenWidthDp
        applyMode()
    }

    /**
     * Настройки меню — одни для главного окна и экрана сообщений (код ревью 4.43: было переписано в обоих окнах).
     * Читаются при каждом возврате на экран: изменения из «Настроек» применяются сразу.
     */
    fun applySettings(settings: com.example.vkweb.settings.AppSettings) {
        setSwipeEnabled(settings.drawerSwipe)
        setMenuHint(!settings.menuDiscovered)
        onMenuDiscovered = { settings.menuDiscovered = true }
        setPinOnWideScreens(settings.pinNavOnWideScreens)
        setStyleChoice(settings.navStyleChoice)
        setLabels(settings.navLabels)
        setExtraSections(settings.openedMenuSections)
        setHiddenTabs(settings.hiddenTabs)
    }

    fun setPinOnWideScreens(pin: Boolean) {
        pinOnWideScreens = pin
        applyMode()
    }

    fun setLabels(show: Boolean) {
        if (show == labels) return
        labels = show
        bars.forEach(::applyLabels)
    }

    private fun applyLabels(bar: com.google.android.material.navigation.NavigationBarView) {
        bar.labelVisibilityMode = if (labels) {
            com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
        } else {
            com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_UNLABELED
        }
        // Высота нижней панели задана в теме под значок с подписью (80 dp); без подписи хватает 56 dp. У полосы слева
        // меняется только подпись: её размер — ширина.
        if (bar === bottomBar) {
            val labeled = labeledMinHeight[bar] ?: bar.minimumHeight
            val compact = (UNLABELED_HEIGHT_DP * bar.resources.displayMetrics.density).toInt()
            bar.minimumHeight = if (labels) labeled else minOf(labeled, compact)
            bar.requestLayout()
        }
    }

    /** Вид меню из настроек (4.64): автоматически, всегда нижняя панель или всегда боковое меню. */
    fun setStyleChoice(choice: NavStyle.Choice) {
        if (choice == styleChoice) return
        styleChoice = choice
        applyMode()
    }

    /** Боковое меню открыто поверх экрана (не закреплённое). */
    val isMenuOpen: Boolean get() = !isPermanent && drawer.isDrawerOpen(navView)

    /** Показывать ☰ до первого открытия меню ([menuHint]). */
    fun setMenuHint(show: Boolean) {
        if (show == menuHint) return
        menuHint = show
        attachToolbar()
    }

    fun setSwipeEnabled(enabled: Boolean) {
        if (enabled == swipeEnabled) return
        swipeEnabled = enabled
        attachToolbar()
        updateGestures()
    }

    /** Убрать разделы из меню (например, клипы при включённом «Скрывать клипы»). */
    fun setHiddenTabs(tabs: Set<Tab>) {
        if (tabs == hiddenTabs) return
        hiddenTabs = tabs
        buildMenu()
        buildBottomMenu()
        highlight(highlighted, highlightedSection)
    }

    /** Открытые в настройках разделы VK: показываются в меню отдельной группой. */
    fun setExtraSections(sections: List<HiddenSection>) {
        if (sections == extraSections) return
        extraSections = sections
        buildMenu()
        highlight(highlighted, highlightedSection)
    }

    /**
     * Отметить текущий пункт меню: раздел [tab] или открытый раздел VK [section] (лента, маркет…); оба null — страница
     * вне меню (профиль, сообщество, фото).
     */
    fun highlight(tab: Tab?, section: HiddenSection? = null) {
        highlighted = tab
        highlightedSection = section
        val menu = navView.menu
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            item.isChecked = when (item.groupId) {
                GROUP_SECTIONS -> tab != null && item.itemId == idOf(tab)
                GROUP_EXTRA -> section != null && item.itemId == EXTRA_ID_BASE + section.ordinal
                else -> false
            }
        }
        // Нижняя панель и полоса: раздел на ней — он; раздел из бокового меню — «Ещё»; страница вне разделов — как было.
        NavStyle.bottomSelection(tab?.let(::idOf), section != null, bottomTabs.map(::idOf), ID_MORE)
            ?.let { id -> bars.forEach { it.menu.findItem(id)?.isChecked = true } }
    }

    /** @return true, если нажатие «Назад» закрыло меню. */
    fun handleBack(): Boolean {
        if (isPermanent || !drawer.isDrawerOpen(navView)) return false
        drawer.closeDrawer(navView)
        return true
    }

    // ------------------------------------------------------------ режимы

    /** Выбирает между скрытым (выезжающим) и закреплённым меню. */
    private fun applyMode() {
        val permanent = pinOnWideScreens && screenWidthDp >= permanentMinWidthDp
        val newStyle = NavStyle.pick(permanent, edgeBackGesture, screenWidthDp, styleChoice)
        // В журнал — при смене и один раз при запуске (4.63: по отчёту иначе не видно, какой вид включился).
        if (newStyle != style || !styleLogged) {
            styleLogged = true
            com.example.vkweb.diag.AppLog.i(
                com.example.vkweb.diag.LogTag.APP, "вид меню", "вид" to newStyle.name,
                "жест «Назад» от края" to edgeBackGesture, "ширина, dp" to screenWidthDp, "выбор" to styleChoice.name,
            )
        }
        style = newStyle
        updateBottomBar(requestInsets = true)
        if (permanent == isPermanent && navView.parent != null) {
            attachToolbar()
            updateGestures()
            return
        }
        isPermanent = permanent

        // closeDrawer бросает исключение, если меню сейчас не в DrawerLayout.
        if (navView.parent === drawer) drawer.closeDrawer(navView, false)
        (navView.parent as? ViewGroup)?.removeView(navView)
        val width = resources.getDimensionPixelSize(R.dimen.nav_width)
        if (permanent) {
            permanentContainer.addView(navView, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            drawer.addView(
                navView, DrawerLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START),
            )
        }
        permanentContainer.visibility = if (permanent) View.VISIBLE else View.GONE
        permanentDivider.visibility = permanentContainer.visibility
        attachToolbar()
        updateGestures()
    }

    /** Разрешает или запрещает жест открытия и резервирует край экрана у системного «Назад». */
    private fun updateGestures() {
        val open = !isPermanent && drawer.isDrawerOpen(navView)
        // У нижней панели меню открывается только кнопкой «Ещё»: свайп от края — системное «Назад», край ему не мешаем.
        val swipe = swipeEnabled && style == NavStyle.DRAWER
        val lockMode = when {
            isPermanent -> DrawerLayout.LOCK_MODE_LOCKED_CLOSED
            // Открытое меню всегда можно закрыть свайпом.
            swipe || open -> DrawerLayout.LOCK_MODE_UNLOCKED
            else -> DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        }
        drawer.setDrawerLockMode(lockMode, GravityCompat.START)
        updateEdgeExclusion(reserve = !isPermanent && swipe && !open)
    }

    /**
     * На Android 10+ с жестовой навигацией свайп от левого края — это системное «Назад».
     * Android разрешает забрать у него полосу высотой не больше 200dp на каждом краю:
     * в ней (по центру экрана) свайп открывает меню, выше и ниже работает «Назад».
     */
    private fun updateEdgeExclusion(reserve: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!reserve || drawer.height == 0) {
            drawer.systemGestureExclusionRects = emptyList()
            return
        }
        val stripHeight = minOf((MAX_EXCLUSION_HEIGHT_DP * density).toInt(), drawer.height)
        val top = (drawer.height - stripHeight) / 2
        val width = (EDGE_WIDTH_DP * density).toInt()
        drawer.systemGestureExclusionRects = listOf(Rect(0, top, width, top + stripHeight))
    }

    // ------------------------------------------------------------ меню

    private fun buildMenu() {
        val menu = navView.menu
        menu.clear()
        Tab.entries.forEachIndexed { index, tab ->
            if (tab in hiddenTabs) return@forEachIndexed
            menu.add(GROUP_SECTIONS, idOf(tab), index, tab.titleRes).setIcon(tab.iconRes).isCheckable = true
        }
        menu.setGroupCheckable(GROUP_SECTIONS, true, true)
        extraSections.forEachIndexed { index, section ->
            menu.add(GROUP_EXTRA, EXTRA_ID_BASE + section.ordinal, Tab.entries.size + index, SectionLabels.of(section))
                .setIcon(SectionLabels.iconOf(section)).isCheckable = true
        }
        menu.setGroupCheckable(GROUP_EXTRA, true, true)
        // NavigationView сам рисует разделитель между группами.
        menu.add(GROUP_APP, ID_SETTINGS, Tab.entries.size + extraSections.size, R.string.menu_settings)
            .setIcon(R.drawable.ic_settings)
    }


    private fun closeIfModal() {
        if (!isPermanent) drawer.closeDrawer(navView)
    }

    private fun idOf(tab: Tab) = ITEM_ID_BASE + tab.ordinal

    private companion object {
        /** Высота нижней панели без подписей: значок с подсветкой и отступы. */
        const val UNLABELED_HEIGHT_DP = 56
        const val AVATAR_DP = 40
        const val GROUP_SECTIONS = 1
        const val GROUP_APP = 2
        const val GROUP_EXTRA = 3
        const val EXTRA_ID_BASE = 3000
        const val ITEM_ID_BASE = 1000
        const val ID_SETTINGS = 2000
        /** «Ещё» на нижней панели. */
        const val ID_MORE = 2001

        /** Разделы нижней панели по порядку (4.50); скрытые в настройках пропускаются, на их место — следующие. */
        val PREFERRED_BOTTOM = listOf(Tab.MESSAGES, Tab.FRIENDS, Tab.GROUPS, Tab.MUSIC, Tab.VIDEO, Tab.CLIPS)

        /** Предел Android для исключений жестов на одном краю. */
        const val MAX_EXCLUSION_HEIGHT_DP = 200

        /** Ширина зоны у края, где DrawerLayout ловит начало свайпа. */
        const val EDGE_WIDTH_DP = 24
    }
}
