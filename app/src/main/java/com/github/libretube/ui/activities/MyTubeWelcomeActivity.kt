package com.github.libretube.ui.activities

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.autofill.AutofillManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.ActivityMytubeWelcomeBinding
import com.github.libretube.enums.SyncServerType
import com.github.libretube.helpers.BackupHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.repo.LibreTubeSyncServerUserDataRepository
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.preferences.BackupRestoreSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/**
 * Наша сборка: первый экран MyTube вместо экрана LibreTube (WelcomeActivity).
 *
 * Сервер синхронизации зашит в сборку (RetrofitInstance, тип LIBRETUBE) — выбора режима нет.
 * Человек вводит имя и пароль и входит, или сам создаёт себе аккаунт. Сервер на неверный пароль
 * и на несуществующее имя отвечает одинаково, поэтому при неудачном входе аккаунт не создаётся
 * молча (опечатка дала бы новый пустой аккаунт), а человека спрашивают.
 */
class MyTubeWelcomeActivity : BaseActivity() {
    private lateinit var binding: ActivityMytubeWelcomeBinding
    private var createMode = false
    private var busy = false

    private val restoreFilePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val restored = runCatching { BackupHelper.restoreAdvancedBackup(this@MyTubeWelcomeActivity, uri) }
                if (restored.isFailure) {
                    Toast.makeText(this@MyTubeWelcomeActivity, R.string.mine_welcome_restore_failed, Toast.LENGTH_LONG).show()
                } else {
                    // копия заменяет все настройки, в том числе вход и тип синхронизации
                    navigateToMain()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMytubeWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // края экрана: полоса состояния, полоса навигации и клавиатура
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            WindowInsetsCompat.CONSUMED
        }

        // HACK из WelcomeActivity: телефоном уже пользовались, если лента подписок загружалась
        binding.localDataWarning.isVisible = PreferenceHelper.getLastCheckedFeedTime(false) != 0L

        createMode = savedInstanceState?.getBoolean(STATE_CREATE_MODE) ?: false
        showMode()

        binding.switchMode.setOnClickListener {
            if (busy) return@setOnClickListener
            switchMode(!createMode)
        }
        binding.submit.setOnClickListener { submit() }
        binding.loginPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) submit()
            false
        }
        binding.createPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) submit()
            false
        }

        binding.loginName.doAfterTextChanged { binding.loginNameLayout.error = null }
        binding.loginPassword.doAfterTextChanged { binding.loginPasswordLayout.error = null }
        binding.createName.doAfterTextChanged { binding.createNameLayout.error = null }
        binding.createPassword.doAfterTextChanged {
            binding.createPasswordLayout.error = null
            updatePasswordHelper()
        }
        updatePasswordHelper()

        binding.withoutAccount.setOnClickListener {
            if (busy) return@setOnClickListener
            PreferenceHelper.putString(PreferenceKeys.SYNC_SERVER_TYPE, SyncServerType.NONE.name.lowercase())
            navigateToMain()
        }
        binding.restore.setOnClickListener {
            if (busy) return@setOnClickListener
            restoreFilePicker.launch(BackupRestoreSettings.JSON)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_CREATE_MODE, createMode)
    }

    override fun requestOrientationChange() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
    }

    private fun switchMode(toCreate: Boolean) {
        // имя и набранный пароль переносятся в другой режим
        if (toCreate) {
            binding.createName.setText(binding.loginName.text)
            binding.createPassword.setText(binding.loginPassword.text)
        } else {
            binding.loginName.setText(binding.createName.text)
            binding.loginPassword.setText(binding.createPassword.text)
        }
        createMode = toCreate
        showMode()
    }

    private fun showMode() {
        binding.loginFields.isVisible = !createMode
        binding.createFields.isVisible = createMode
        binding.subtitle.setText(
            if (createMode) R.string.mine_welcome_create_subtitle else R.string.mine_welcome_login_subtitle
        )
        binding.submit.setText(if (createMode) R.string.mine_welcome_create else R.string.mine_welcome_login)
        binding.switchMode.setText(if (createMode) R.string.mine_welcome_to_login else R.string.mine_welcome_to_create)
    }

    private fun updatePasswordHelper() {
        val length = binding.createPassword.text?.length ?: 0
        binding.createPasswordLayout.helperText = if (length < MIN_PASSWORD_LENGTH) {
            getString(R.string.mine_welcome_password_short, MIN_PASSWORD_LENGTH, length)
        } else {
            getString(R.string.mine_welcome_password_ok)
        }
    }

    private fun submit() {
        if (busy) return
        if (createMode) submitCreate() else submitLogin()
    }

    private fun submitLogin() {
        val name = normalizeName(binding.loginName.text)
        val password = binding.loginPassword.text?.toString().orEmpty()
        if (name.isEmpty()) {
            binding.loginNameLayout.error = getString(R.string.mine_welcome_enter_name)
            return
        }
        if (password.isEmpty()) {
            binding.loginPasswordLayout.error = getString(R.string.mine_welcome_enter_password)
            return
        }

        runRequest(register = false, name, password) { error ->
            when {
                error.isWrongLogin() -> askCreateAccount(name, password)
                else -> binding.loginPasswordLayout.error = describe(error)
            }
        }
    }

    private fun submitCreate(afterFailedLogin: Boolean = false) {
        val name = normalizeName(binding.createName.text)
        val password = binding.createPassword.text?.toString().orEmpty()
        if (name.isEmpty()) {
            binding.createNameLayout.error = getString(R.string.mine_welcome_enter_name)
            return
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            binding.createPasswordLayout.error =
                getString(R.string.mine_welcome_password_short, MIN_PASSWORD_LENGTH, password.length)
            binding.createPassword.requestFocus()
            return
        }

        runRequest(register = true, name, password) { error ->
            when {
                // «Аккаунта нет?» → «Создать», а имя занято: значит, аккаунт есть и неверен пароль
                error.isNameTaken() && afterFailedLogin -> {
                    switchMode(toCreate = false)
                    binding.loginPasswordLayout.error = getString(R.string.mine_welcome_wrong_password, name)
                    binding.loginPassword.requestFocus()
                }

                error.isNameTaken() -> {
                    binding.createNameLayout.error = getString(R.string.mine_welcome_name_taken)
                    binding.createName.requestFocus()
                }

                else -> binding.createPasswordLayout.error = describe(error)
            }
        }
    }

    private fun askCreateAccount(name: String, password: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mine_welcome_no_account_title)
            .setMessage(getString(R.string.mine_welcome_no_account_message, name))
            .setPositiveButton(R.string.mine_welcome_no_account_create) { _, _ ->
                switchMode(toCreate = true)
                // пароль короче нужного — остаёмся на экране создания с подсказкой
                if (password.length >= MIN_PASSWORD_LENGTH) submitCreate(afterFailedLogin = true)
                else binding.createPassword.requestFocus()
            }
            .setNegativeButton(R.string.mine_welcome_no_account_fix, null)
            .show()
    }

    private fun runRequest(
        register: Boolean,
        name: String,
        password: String,
        onError: (Exception) -> Unit
    ) {
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val repository = LibreTubeSyncServerUserDataRepository()
                    Result.success(
                        if (register) repository.register(name, password) else repository.login(name, password)
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            setBusy(false)

            result.onSuccess { token ->
                PreferenceHelper.putString(PreferenceKeys.SYNC_SERVER_TYPE, SyncServerType.LIBRETUBE.name.lowercase())
                PreferenceHelper.setToken(token)
                PreferenceHelper.setUsername(name)
                // менеджер паролей сохраняет имя и пароль сейчас, а не когда-нибудь потом
                getSystemService(AutofillManager::class.java)?.commit()
                navigateToMain()
            }.onFailure { onError(it as Exception) }
        }
    }

    private fun setBusy(isBusy: Boolean) {
        busy = isBusy
        binding.progress.visibility = if (isBusy) View.VISIBLE else View.INVISIBLE
        binding.submit.isEnabled = !isBusy
        if (isBusy) {
            getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        }
    }

    private fun describe(error: Exception): String {
        val message = error.message.orEmpty()
        return when {
            error is IOException -> getString(R.string.mine_welcome_no_connection)
            error.isNameTaken() -> getString(R.string.mine_welcome_name_taken)
            message.contains("data breach") -> getString(R.string.mine_welcome_password_breached)
            message.contains("too short") -> getString(
                R.string.mine_welcome_password_short, MIN_PASSWORD_LENGTH,
                binding.createPassword.text?.length ?: 0
            )
            else -> getString(R.string.mine_welcome_server_error, message.ifEmpty { error.javaClass.simpleName })
        }
    }

    private fun navigateToMain() {
        markDone()
        // из настроек («Аккаунт» → «Вы не вошли») — просто вернуться туда
        if (!intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    // ответы sync-server (libre-tube/sync-server): 403 «invalid accountname or password»,
    // 409 «accountname already taken», 400 «password too short» / «password is included in a data breach»
    private fun Exception.isWrongLogin() = message.orEmpty().contains("invalid accountname or password")
    private fun Exception.isNameTaken() = message.orEmpty().contains("already taken")

    companion object {
        const val EXTRA_FROM_SETTINGS = "from_settings"
        private const val STATE_CREATE_MODE = "create_mode"
        private const val MYTUBE_WELCOME_DONE = "mytube_welcome_done"

        /** Требование sync-server к паролю. */
        const val MIN_PASSWORD_LENGTH = 25

        /**
         * Имя без пробелов по краям и строчными: клавиатура телефона сама ставит заглавную,
         * и «Andrey» вместо «andrey» было бы другим аккаунтом.
         */
        fun normalizeName(text: CharSequence?) = text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()

        /**
         * Показывать ли экран: при первом запуске и тем, кто прошёл экран LibreTube без входа
         * (MyTube до 32.1-mytube.4) — один раз; после «Войти», «Без аккаунта» или
         * восстановления из копии больше не показывается.
         */
        fun shouldShow() = !PreferenceHelper.getBoolean(MYTUBE_WELCOME_DONE, false) &&
            PreferenceHelper.getToken().isBlank()

        private fun markDone() {
            PreferenceHelper.putBoolean(MYTUBE_WELCOME_DONE, true)
            PreferenceHelper.putBoolean(PreferenceKeys.WELCOME_ACTIVITY_FINISHED, true)
        }
    }
}
