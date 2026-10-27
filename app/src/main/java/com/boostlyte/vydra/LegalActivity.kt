package com.boostlyte.vydra

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.InputStreamReader

/**
 * In-app legal document viewer displaying the Privacy Policy or Terms of Use.
 * Loads verified text directly from local assets to guarantee offline availability.
 */
class LegalActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DOC_TYPE = "EXTRA_DOC_TYPE"
        const val DOC_PRIVACY_POLICY = "privacy_policy"
        const val DOC_TERMS_OF_USE = "terms_of_use"

        fun start(context: Context, docType: String) {
            val intent = Intent(context, LegalActivity::class.java).apply {
                putExtra(EXTRA_DOC_TYPE, docType)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_legal)

        val btnBack = findViewById<ImageButton>(R.id.btnLegalBack)
        val tvTitle = findViewById<TextView>(R.id.tvLegalTitle)
        val tvContent = findViewById<TextView>(R.id.tvLegalContent)
        val cardHighlights = findViewById<View>(R.id.cardLegalHighlights)

        btnBack.setOnClickListener {
            finish()
        }

        val docType = intent.getStringExtra(EXTRA_DOC_TYPE) ?: DOC_PRIVACY_POLICY

        if (docType == DOC_TERMS_OF_USE) {
            tvTitle.text = getString(R.string.str_terms_of_use)
            cardHighlights.visibility = View.VISIBLE
            loadDocument("legal/terms_of_use.txt", tvContent)
        } else {
            tvTitle.text = getString(R.string.str_privacy_policy)
            cardHighlights.visibility = View.VISIBLE
            loadDocument("legal/privacy_policy.txt", tvContent)
        }
    }

    private fun loadDocument(assetPath: String, targetTextView: TextView) {
        try {
            assets.open(assetPath).use { stream ->
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    val content = reader.readText()
                    targetTextView.text = content
                }
            }
        } catch (e: Exception) {
            targetTextView.text = "Unable to load document: ${e.message}"
        }
    }
}
