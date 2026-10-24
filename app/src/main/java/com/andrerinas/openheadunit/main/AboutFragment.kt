package com.andrerinas.openheadunit.main

import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.BuildConfig
import com.google.android.material.appbar.MaterialToolbar
import java.util.Calendar

class AboutFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_about, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        val copyrightText = view.findViewById<TextView>(R.id.copyright_text)
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        copyrightText.text = getString(R.string.copyright, currentYear)

        val contentText = view.findViewById<TextView>(R.id.about_content_text)
        
        val sb = StringBuilder()
        sb.append("<b>Special thanks to Mike Reidis for the original idea and android auto protocol and code.</b><br/>")
        sb.append("<a href=\"https://github.com/mikereidis/headunit\">https://github.com/mikereidis/headunit</a><br/><br/>")
        sb.append("<h3>Issues, bugs, and feedback or questions</h3>")
        sb.append("If you need any help, go to the github page of this app. You will additionally can support me via <a href=\"https://www.paypal.me/anrinas\">Paypal</a><br/>")
        sb.append("<a href=\"https://github.com/andreknieriem/open-headunit\">https://github.com/andreknieriem/open-headunit</a><br/><br/>")

        sb.append("<p>Build: ${TextUtils.htmlEncode(BuildConfig.GIT_SHA)}</p>")
        sb.append(parseMarkdownToHtml(readAsset("CHANGELOG.md")))
        sb.append("<br/><br/>")

        sb.append("<h3>SoundTouch / Rubber Band — licenses &amp; source</h3>")
        // Keep notices available offline, and preserve literal <...> in license texts.
        sb.append(TextUtils.htmlEncode(readAsset("audio-third-party.txt")).replace("\n", "<br/>"))
        sb.append("<br/><br/><h3>LICENSE</h3>")
        sb.append(TextUtils.htmlEncode(readAsset("LICENSE")).replace("\n", "<br/>"))

        contentText.text = fromHtml(sb.toString())
        contentText.movementMethod = android.text.method.LinkMovementMethod.getInstance() // Make links clickable
    }

    private fun readAsset(fileName: String): String {
        return try {
            requireContext().assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Error loading $fileName"
        }
    }

    private fun parseMarkdownToHtml(markdown: String): String {
        var html = markdown
        // Headers
        html = html.replace(Regex("### (.*)"), "<h4>$1</h4>")
        html = html.replace(Regex("## (.*)"), "<h3>$1</h3>")
        html = html.replace(Regex("# (.*)"), "<h2>$1</h2>")
        
        // Bold
        html = html.replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
        
        // Lists
        html = html.replace(Regex("\n- (.*)"), "<br/>&#8226; $1")
        
        // Newlines (Markdown preserves single newlines as space, but we want break for readability in log?)
        // Actually, let's just replace double newlines with paragraph, and single with br?
        // Simple approach: Replace \n with <br/> but be careful not to break tags.
        // For list items we already handled the newline prefix.
        
        // Let's replace remaining newlines that are not part of tags
        // This is tricky with regex. 
        // Better: replace all \n with <br/> at the end?
        // The list replacement consumed the \n before the dash.
        
        return html
    }

    private fun fromHtml(html: String): Spanned {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html)
        }
    }
}
