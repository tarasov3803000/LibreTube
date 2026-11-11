package com.github.libretube.ui.preferences

import android.content.Intent
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.enums.SyncServerType
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.ui.activities.MyTubeWelcomeActivity

/**
 * Наша сборка: экран «Аккаунт» (настройки LibreTube DataManagementSettings) по-простому.
 * Сверху — под каким именем вошли; выбор сервера синхронизации, его адрес и вход LibreTube
 * спрятаны: сервер зашит в сборку, войти или создать аккаунт — на нашем первом экране.
 */
object MyTubeAccount {
    private const val KEY = "mytube_account"

    fun update(fragment: PreferenceFragmentCompat) {
        val context = fragment.context ?: return
        val screen = fragment.preferenceScreen ?: return

        listOf(
            PreferenceKeys.SYNC_SERVER_TYPE,
            PreferenceKeys.AUTH_INSTANCE,
            PreferenceKeys.LIBRETUBE_SYNC_SERVER_URL,
            PreferenceKeys.LOGIN_REGISTER
        ).forEach { screen.findPreference<Preference>(it)?.isVisible = false }

        val category = screen.findPreference<Preference>(PreferenceKeys.LOGOUT)?.parent ?: return
        category.title = null // экран и так называется «Аккаунт»
        val account = category.findPreference(KEY) ?: Preference(context).apply {
            key = KEY
            order = -1 // первой в разделе
            category.addPreference(this)
        }

        val loggedIn = PreferenceHelper.getToken().isNotBlank() &&
            UserDataRepositoryHelper.syncServerType == SyncServerType.LIBRETUBE
        if (loggedIn) {
            val name = PreferenceHelper.getUsername()
            account.setIcon(R.drawable.ic_person)
            account.title = if (name.isNotBlank()) {
                context.getString(R.string.mine_account_signed_in, name)
            } else {
                context.getString(R.string.mine_account_signed_in_no_name)
            }
            account.summary = context.getString(R.string.mine_account_signed_in_summary)
            account.onPreferenceClickListener = null
            account.isSelectable = false
        } else {
            account.setIcon(R.drawable.ic_login_filled)
            account.title = context.getString(R.string.mine_account_signed_out)
            account.summary = context.getString(R.string.mine_account_signed_out_summary)
            account.isSelectable = true
            account.setOnPreferenceClickListener {
                fragment.startActivity(
                    Intent(context, MyTubeWelcomeActivity::class.java)
                        .putExtra(MyTubeWelcomeActivity.EXTRA_FROM_SETTINGS, true)
                )
                true
            }
        }
    }
}
