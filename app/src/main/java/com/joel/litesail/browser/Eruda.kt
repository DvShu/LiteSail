package com.joel.litesail.browser

import android.net.Uri
import android.webkit.WebView
import org.json.JSONObject

internal object Eruda {
    fun setEnabled(view: WebView, enabled: Boolean) {
        val url = view.url ?: return
        if (!BrowserAddress.isWebScheme(Uri.parse(url).scheme)) return

        view.evaluateJavascript(
            """
            (() => {
                const current = window.__liteSailEruda;
                if (!$enabled) {
                    if (current) current.setEnabled(false);
                    return;
                }
                if (location.href !== ${JSONObject.quote(url)} || document.readyState !== "complete") return;
                if (current) {
                    current.setEnabled(true);
                    return;
                }
                if (window.eruda) return;

                let enabled = true;
                let initialized = false;
                let eruda;

                function destroy() {
                    try {
                        eruda.destroy();
                    } catch (error) {
                        // Eruda may only be partially initialized.
                    }
                    initialized = false;
                }

                function setEnabled(value) {
                    enabled = value;
                    if (!eruda || initialized === enabled) return;
                    if (!enabled) {
                        destroy();
                        return;
                    }
                    try {
                        eruda.init();
                        initialized = true;
                    } catch (error) {
                        destroy();
                    }
                }

                const script = document.createElement("script");
                function deactivate() {
                    setEnabled(false);
                }
                function discard() {
                    script.remove();
                    window.removeEventListener("pagehide", deactivate);
                    delete window.__liteSailEruda;
                }

                script.async = true;
                script.referrerPolicy = "no-referrer";
                script.onload = () => {
                    const instance = window.eruda;
                    if (!instance || typeof instance.init !== "function" || typeof instance.destroy !== "function") {
                        discard();
                        return;
                    }
                    script.remove();
                    eruda = instance;
                    setEnabled(enabled);
                };
                script.onerror = discard;
                window.__liteSailEruda = { setEnabled };
                window.addEventListener("pagehide", deactivate);
                try {
                    script.src = "https://cdn.jsdelivr.net/npm/eruda";
                    document.head.appendChild(script);
                } catch (error) {
                    discard();
                }
            })();
            """.trimIndent(),
            null
        )
    }
}
