package com.looker.droidify

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.looker.droidify.database.CursorOwner
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.extension.getThemeRes
import com.looker.droidify.datastore.get
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.model.installFrom
import com.looker.droidify.ui.appDetail.AppDetailFragment
import com.looker.droidify.ui.favourites.FavouritesFragment
import com.looker.droidify.ui.repository.EditRepositoryFragment
import com.looker.droidify.ui.repository.RepositoriesFragment
import com.looker.droidify.ui.repository.RepositoryFragment
import com.looker.droidify.ui.settings.SettingsFragment
import com.looker.droidify.ui.tabsFragment.TabsFragment
import com.looker.droidify.utility.common.DeeplinkType
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.deeplinkType
import com.looker.droidify.utility.common.extension.homeAsUp
import com.looker.droidify.utility.common.extension.inputManager
import com.looker.droidify.utility.common.getInstallPackageName
import com.looker.droidify.utility.common.requestNotificationPermission
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    companion object {
        private const val STATE_FRAGMENT_STACK = "fragmentStack"
        const val ACTION_UPDATES = "${BuildConfig.APPLICATION_ID}.intent.action.UPDATES"
        const val ACTION_INSTALL = "${BuildConfig.APPLICATION_ID}.intent.action.INSTALL"
        const val EXTRA_CACHE_FILE_NAME =
            "${BuildConfig.APPLICATION_ID}.intent.extra.CACHE_FILE_NAME"
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @Inject
    lateinit var installer: InstallManager

    @Parcelize
    private class FragmentStackItem(
        val className: String, val arguments: Bundle?, val savedState: Fragment.SavedState?,
    ) : Parcelable

    lateinit var cursorOwner: CursorOwner
        private set

    private var onBackPressedCallback: OnBackPressedCallback? = null

    private val fragmentStack = mutableListOf<FragmentStackItem>()

    private val currentFragment: Fragment?
        get() {
            supportFragmentManager.executePendingTransactions()
            return supportFragmentManager.findFragmentById(R.id.main_content)
        }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CustomUserRepositoryInjector {
        fun settingsRepository(): SettingsRepository
    }

    private fun collectChange() {
        val hiltEntryPoint =
            EntryPointAccessors.fromApplication(this, CustomUserRepositoryInjector::class.java)
        val newSettings = hiltEntryPoint.settingsRepository().get { theme to dynamicTheme }
        runBlocking {
            val theme = newSettings.first()
            setTheme(
                resources.configuration.getThemeRes(
                    theme = theme.first,
                    dynamicTheme = theme.second,
                ),
            )
        }
        lifecycleScope.launch {
            newSettings.drop(1).collect { themeAndDynamic ->
                setTheme(
                    resources.configuration.getThemeRes(
                        theme = themeAndDynamic.first,
                        dynamicTheme = themeAndDynamic.second,
                    ),
                )
                recreate()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        collectChange()
        super.onCreate(savedInstanceState)
        val rootView = FrameLayout(this).apply { id = R.id.main_content }
        addContentView(
            rootView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        requestNotificationPermission(request = notificationPermission::launch)

        supportFragmentManager.addFragmentOnAttachListener { _, _ ->
            hideKeyboard()
        }

        if (savedInstanceState == null) {
            cursorOwner = CursorOwner()
            supportFragmentManager.commit {
                add(cursorOwner, CursorOwner::class.java.name)
            }
        } else {
            cursorOwner =
                supportFragmentManager.findFragmentByTag(CursorOwner::class.java.name) as CursorOwner
        }

        savedInstanceState?.getParcelableArrayList<FragmentStackItem>(STATE_FRAGMENT_STACK)
            ?.let { fragmentStack += it }
        if (savedInstanceState == null) {
            replaceFragment(TabsFragment(), null)
            if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
                handleIntent(intent)
            }
        }
        if (SdkCheck.isR) {
            window.statusBarColor = resources.getColor(android.R.color.transparent, theme)
            window.navigationBarColor = resources.getColor(android.R.color.transparent, theme)
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        backHandler()
    }

    override fun onDestroy() {
        super.onDestroy()
        onBackPressedCallback = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(STATE_FRAGMENT_STACK, ArrayList(fragmentStack))
    }

    private fun backHandler() {
        if (onBackPressedCallback == null) {
            onBackPressedCallback = object : OnBackPressedCallback(enabled = false) {
                override fun handleOnBackPressed() {
                    hideKeyboard()
                    popFragment()
                }
            }
            onBackPressedDispatcher.addCallback(
                this,
                onBackPressedCallback!!,
            )
        }
        onBackPressedCallback?.isEnabled = fragmentStack.isNotEmpty()
    }

    private fun replaceFragment(fragment: Fragment, open: Boolean?) {
        if (open != null) {
            currentFragment?.view?.translationZ =
                (if (open) Int.MIN_VALUE else Int.MAX_VALUE).toFloat()
        }
        supportFragmentManager.commit {
            if (open != null) {
                setCustomAnimations(
                    if (open) R.animator.slide_in else 0,
                    if (open) R.animator.slide_in_keep else R.animator.slide_out,
                )
            }
            setReorderingAllowed(true)
            replace(R.id.main_content, fragment)
        }
    }

    private fun pushFragment(fragment: Fragment) {
        currentFragment?.let {
            fragmentStack.add(
                FragmentStackItem(
                    it::class.java.name,
                    it.arguments,
                    supportFragmentManager.saveFragmentInstanceState(it),
                ),
            )
        }
        replaceFragment(fragment, true)
        backHandler()
    }

    private fun popFragment(): Boolean {
        return fragmentStack.isNotEmpty() && run {
            val stackItem = fragmentStack.removeAt(fragmentStack.size - 1)
            val fragment = Class.forName(stackItem.className).newInstance() as Fragment
            stackItem.arguments?.let(fragment::setArguments)
            stackItem.savedState?.let(fragment::setInitialSavedState)
            replaceFragment(fragment, false)
            backHandler()
            true
        }
    }

    private fun hideKeyboard() {
        inputManager?.hideSoftInputFromWindow((currentFocus ?: window.decorView).windowToken, 0)
    }

    internal fun onToolbarCreated(toolbar: Toolbar) {
        if (fragmentStack.isNotEmpty()) {
            toolbar.navigationIcon = toolbar.context.homeAsUp
            toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_UPDATES -> {
                if (currentFragment !is TabsFragment) {
                    fragmentStack.clear()
                    replaceFragment(TabsFragment(), true)
                }
                val tabsFragment = currentFragment as TabsFragment
                tabsFragment.selectUpdates()
                backHandler()
            }

            ACTION_INSTALL -> {
                val packageName = intent.getInstallPackageName
                if (!packageName.isNullOrEmpty()) {
                    navigateProduct(packageName)
                    val cacheFile = intent.getStringExtra(EXTRA_CACHE_FILE_NAME) ?: return
                    val installItem = packageName installFrom cacheFile
                    lifecycleScope.launch { installer install installItem }
                }
            }

            Intent.ACTION_VIEW -> {
                when (val deeplink = intent.deeplinkType) {
                    is DeeplinkType.AppDetail -> {
                        val fragment = currentFragment
                        if (fragment !is AppDetailFragment) {
                            navigateProduct(deeplink.packageName, deeplink.repoAddress)
                        }
                    }

                    is DeeplinkType.AddRepository -> {
                        navigateAddRepository(repoAddress = deeplink.address)
                    }

                    null -> {}
                }
            }

            Intent.ACTION_SHOW_APP_INFO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)

                    if (packageName != null && currentFragment !is AppDetailFragment) {
                        navigateProduct(packageName)
                    }
                }

            }
        }
    }

    fun navigateFavourites() = pushFragment(FavouritesFragment())
    fun navigateProduct(packageName: String, repoAddress: String? = null) =
        pushFragment(AppDetailFragment(packageName, repoAddress))

    fun navigateRepositories() = pushFragment(RepositoriesFragment())
    fun navigatePreferences() = pushFragment(SettingsFragment.newInstance())
    fun navigateAddRepository(repoAddress: String? = null) =
        pushFragment(EditRepositoryFragment(null, repoAddress))

    fun navigateRepository(repositoryId: Long) =
        pushFragment(RepositoryFragment(repositoryId))

    fun navigateEditRepository(repositoryId: Long) =
        pushFragment(EditRepositoryFragment(repositoryId, null))
}
