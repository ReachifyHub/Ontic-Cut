package com.boostlyte.vydra.auth

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.boostlyte.vydra.R
import com.boostlyte.vydra.transcription.SupabaseAuthManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BottomSheetDialog for Supabase user authentication.
 *
 * Implements traditional Android Views to support:
 * - Switching between Sign In and Sign Up tabs
 * - Password recovery (Forgot Password)
 * - Email confirmation instructions with a live Resend button
 * - Automatic execution of [onAuthenticated] callback upon successful sign in/up
 */
class AuthBottomSheetDialog(
    private val context: Context,
    private val initialEmail: String? = null,
    private val onAuthenticated: ((email: String) -> Unit)? = null
) {

    private val dialog: BottomSheetDialog = BottomSheetDialog(context)
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private var isSignUpMode: Boolean = false
    private var lastAttemptedEmail: String = ""

    // UI references
    private lateinit var btnCloseAuth: ImageButton
    private lateinit var btnTabSignIn: TextView
    private lateinit var btnTabSignUp: TextView
    private lateinit var layoutAuthError: LinearLayout
    private lateinit var tvAuthErrorMessage: TextView

    // Form
    private lateinit var layoutAuthForm: LinearLayout
    private lateinit var etAuthEmail: EditText
    private lateinit var etAuthPassword: EditText
    private lateinit var tvForgotPassword: TextView
    private lateinit var btnAuthSubmit: Button
    private lateinit var progressAuth: ProgressBar

    // Confirmation
    private lateinit var layoutConfirmEmail: LinearLayout
    private lateinit var tvConfirmEmailDesc: TextView
    private lateinit var btnResendConfirmation: Button
    private lateinit var btnBackToSignInFromConfirm: Button
    private lateinit var progressResendEmail: ProgressBar

    // Password Reset
    private lateinit var layoutPasswordRecovery: LinearLayout
    private lateinit var etResetPasswordEmail: EditText
    private lateinit var btnSendPasswordReset: Button
    private lateinit var btnBackToSignInFromReset: Button
    private lateinit var progressResetPassword: ProgressBar

    init {
        setupView()
    }

    private fun setupView() {
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_auth, null)
        dialog.setContentView(view)

        btnCloseAuth = view.findViewById(R.id.btnCloseAuth)
        btnTabSignIn = view.findViewById(R.id.btnTabSignIn)
        btnTabSignUp = view.findViewById(R.id.btnTabSignUp)
        layoutAuthError = view.findViewById(R.id.layoutAuthError)
        tvAuthErrorMessage = view.findViewById(R.id.tvAuthErrorMessage)

        layoutAuthForm = view.findViewById(R.id.layoutAuthForm)
        etAuthEmail = view.findViewById(R.id.etAuthEmail)
        etAuthPassword = view.findViewById(R.id.etAuthPassword)
        tvForgotPassword = view.findViewById(R.id.tvForgotPassword)
        btnAuthSubmit = view.findViewById(R.id.btnAuthSubmit)
        progressAuth = view.findViewById(R.id.progressAuth)

        layoutConfirmEmail = view.findViewById(R.id.layoutConfirmEmail)
        tvConfirmEmailDesc = view.findViewById(R.id.tvConfirmEmailDesc)
        btnResendConfirmation = view.findViewById(R.id.btnResendConfirmation)
        btnBackToSignInFromConfirm = view.findViewById(R.id.btnBackToSignInFromConfirm)
        progressResendEmail = view.findViewById(R.id.progressResendEmail)

        layoutPasswordRecovery = view.findViewById(R.id.layoutPasswordRecovery)
        etResetPasswordEmail = view.findViewById(R.id.etResetPasswordEmail)
        btnSendPasswordReset = view.findViewById(R.id.btnSendPasswordReset)
        btnBackToSignInFromReset = view.findViewById(R.id.btnBackToSignInFromReset)
        progressResetPassword = view.findViewById(R.id.progressResetPassword)

        if (!initialEmail.isNullOrBlank()) {
            etAuthEmail.setText(initialEmail)
        }

        btnCloseAuth.setOnClickListener { dialog.dismiss() }

        btnTabSignIn.setOnClickListener { setMode(signUp = false) }
        btnTabSignUp.setOnClickListener { setMode(signUp = true) }

        btnAuthSubmit.setOnClickListener { submitAuth() }

        tvForgotPassword.setOnClickListener { showPasswordRecovery() }

        btnBackToSignInFromConfirm.setOnClickListener {
            showFormView()
            setMode(signUp = false)
        }

        btnBackToSignInFromReset.setOnClickListener {
            showFormView()
            setMode(signUp = false)
        }

        btnResendConfirmation.setOnClickListener { resendConfirmation() }
        btnSendPasswordReset.setOnClickListener { sendPasswordReset() }

        setMode(signUp = false)
    }

    private fun setMode(signUp: Boolean) {
        isSignUpMode = signUp
        clearError()

        if (signUp) {
            btnTabSignUp.setBackgroundResource(R.drawable.bg_tab_active_pill)
            btnTabSignUp.setTextColor(ContextCompat.getColor(context, R.color.colorOnPrimary))

            btnTabSignIn.setBackgroundColor(Color.TRANSPARENT)
            btnTabSignIn.setTextColor(ContextCompat.getColor(context, R.color.toolTextInactive))

            btnAuthSubmit.text = context.getString(R.string.auth_btn_sign_up)
            tvForgotPassword.visibility = View.GONE
        } else {
            btnTabSignIn.setBackgroundResource(R.drawable.bg_tab_active_pill)
            btnTabSignIn.setTextColor(ContextCompat.getColor(context, R.color.colorOnPrimary))

            btnTabSignUp.setBackgroundColor(Color.TRANSPARENT)
            btnTabSignUp.setTextColor(ContextCompat.getColor(context, R.color.toolTextInactive))

            btnAuthSubmit.text = context.getString(R.string.auth_btn_sign_in)
            tvForgotPassword.visibility = View.VISIBLE
        }
    }

    private fun submitAuth() {
        val email = etAuthEmail.text.toString().trim()
        val password = etAuthPassword.text.toString()

        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError(context.getString(R.string.auth_err_invalid_email))
            return
        }

        if (password.length < 6) {
            showError(context.getString(R.string.auth_err_short_password))
            return
        }

        lastAttemptedEmail = email
        clearError()
        setLoading(true)

        coroutineScope.launch {
            val result = if (isSignUpMode) {
                SupabaseAuthManager.signUp(context, email, password)
            } else {
                SupabaseAuthManager.signIn(context, email, password)
            }

            setLoading(false)

            when (result) {
                is AuthResult.Success -> {
                    if (result.session != null) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.auth_signed_in_toast, email),
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                        onAuthenticated?.invoke(email)
                    } else {
                        // Email confirmation is required
                        showEmailConfirmationView(email)
                    }
                }
                is AuthResult.NeedsEmailConfirmation -> {
                    showEmailConfirmationView(result.email)
                }
                is AuthResult.Error -> {
                    if (result.code == AuthErrorCode.EMAIL_NOT_CONFIRMED) {
                        showEmailConfirmationView(email)
                    } else {
                        showError(result.message)
                    }
                }
            }
        }
    }

    private fun showEmailConfirmationView(email: String) {
        lastAttemptedEmail = email
        layoutAuthForm.visibility = View.GONE
        layoutPasswordRecovery.visibility = View.GONE
        layoutConfirmEmail.visibility = View.VISIBLE
        tvConfirmEmailDesc.text = context.getString(R.string.auth_confirm_email_desc, email)
        clearError()
    }

    private fun showPasswordRecovery() {
        layoutAuthForm.visibility = View.GONE
        layoutConfirmEmail.visibility = View.GONE
        layoutPasswordRecovery.visibility = View.VISIBLE
        etResetPasswordEmail.setText(etAuthEmail.text.toString().trim())
        clearError()
    }

    private fun showFormView() {
        layoutConfirmEmail.visibility = View.GONE
        layoutPasswordRecovery.visibility = View.GONE
        layoutAuthForm.visibility = View.VISIBLE
        clearError()
    }

    private fun resendConfirmation() {
        val email = lastAttemptedEmail.ifBlank { etAuthEmail.text.toString().trim() }
        if (email.isBlank()) {
            showError(context.getString(R.string.auth_err_invalid_email))
            return
        }

        progressResendEmail.visibility = View.VISIBLE
        btnResendConfirmation.isEnabled = false

        coroutineScope.launch {
            val result = SupabaseAuthManager.resendConfirmationEmail(email)
            progressResendEmail.visibility = View.GONE
            btnResendConfirmation.isEnabled = true

            when (result) {
                is AuthResult.Success -> {
                    Toast.makeText(context, context.getString(R.string.auth_email_resent), Toast.LENGTH_LONG).show()
                }
                is AuthResult.Error -> {
                    showError(result.message)
                }
                else -> {}
            }
        }
    }

    private fun sendPasswordReset() {
        val email = etResetPasswordEmail.text.toString().trim()
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError(context.getString(R.string.auth_err_invalid_email))
            return
        }

        progressResetPassword.visibility = View.VISIBLE
        btnSendPasswordReset.isEnabled = false

        coroutineScope.launch {
            val result = SupabaseAuthManager.sendPasswordReset(email)
            progressResetPassword.visibility = View.GONE
            btnSendPasswordReset.isEnabled = true

            when (result) {
                is AuthResult.Success -> {
                    Toast.makeText(context, context.getString(R.string.auth_reset_link_sent), Toast.LENGTH_LONG).show()
                    showFormView()
                    setMode(signUp = false)
                }
                is AuthResult.Error -> {
                    showError(result.message)
                }
                else -> {}
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressAuth.visibility = if (loading) View.VISIBLE else View.GONE
        btnAuthSubmit.isEnabled = !loading
        btnTabSignIn.isEnabled = !loading
        btnTabSignUp.isEnabled = !loading
        etAuthEmail.isEnabled = !loading
        etAuthPassword.isEnabled = !loading
    }

    private fun showError(message: String) {
        layoutAuthError.visibility = View.VISIBLE
        tvAuthErrorMessage.text = message
    }

    private fun clearError() {
        layoutAuthError.visibility = View.GONE
        tvAuthErrorMessage.text = ""
    }

    fun show() {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) {
            return
        }
        dialog.show()
    }

    fun dismiss() {
        dialog.dismiss()
    }
}
