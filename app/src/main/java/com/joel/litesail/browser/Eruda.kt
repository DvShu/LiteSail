package com.joel.litesail.browser

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

internal object Eruda {
    private val bootstrap = """
            (() => {
                if (window !== window.top || (location.protocol !== "http:" && location.protocol !== "https:")) return;
                const current = window.__liteSailEruda;
                if (current) {
                    current.setEnabled(true);
                    return;
                }
                if (window.eruda) return;

                let enabled = true;
                let initialized = false;
                let eruda;
                let script;
                let headObserver;

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
                    if (enabled) {
                        initialize();
                        return;
                    }
                    if (headObserver) discard();
                    document.removeEventListener("DOMContentLoaded", initialize);
                    if (initialized) destroy();
                }

                function initialize() {
                    if (!enabled || !eruda || initialized) return;
                    if (!document.body && document.readyState === "loading") {
                        document.addEventListener("DOMContentLoaded", initialize, { once: true });
                        return;
                    }
                    try {
                        eruda.init();
                        initialized = true;
                    } catch (error) {
                        destroy();
                    }
                }

                function deactivate() {
                    setEnabled(false);
                }

                function stopWaitingForHead() {
                    if (headObserver) {
                        headObserver.disconnect();
                        headObserver = null;
                    }
                    document.removeEventListener("DOMContentLoaded", tryLoadScript);
                }

                function discard() {
                    if (script) script.remove();
                    stopWaitingForHead();
                    window.removeEventListener("pagehide", deactivate);
                    delete window.__liteSailEruda;
                }

                function onLoaded(instance) {
                    if (!instance || typeof instance.init !== "function" || typeof instance.destroy !== "function") {
                        discard();
                        return;
                    }
                    if (script) {
                        script.remove();
                        script = null;
                    }
                    eruda = instance;
                    initialize();
                }

                function tryLoadScript() {
                    const head = document.head;
                    if (!head) {
                        if (document.readyState !== "loading") discard();
                        return;
                    }
                    stopWaitingForHead();
                    if (!enabled || window.eruda) {
                        discard();
                        return;
                    }
                    try {
                        const url = "https://cdn.jsdelivr.net/npm/eruda";
                        const amdRequire = typeof window.requirejs === "function" ? window.requirejs : window.require;
                        if (typeof window.define === "function" && window.define.amd && typeof amdRequire === "function") {
                            // Keep an AMD loader's added .js suffix out of the CDN request path.
                            amdRequire([url + "#litesail"], onLoaded, discard);
                            return;
                        }

                        script = document.createElement("script");
                        script.async = true;
                        script.referrerPolicy = "no-referrer";
                        script.onload = () => onLoaded(window.eruda);
                        script.onerror = discard;
                        script.src = url;
                        head.appendChild(script);
                    } catch (error) {
                        discard();
                    }
                }

                window.__liteSailEruda = { setEnabled };
                window.addEventListener("pagehide", deactivate);
                if (!document.head && document.readyState === "loading") {
                    headObserver = new MutationObserver(tryLoadScript);
                    headObserver.observe(document, { childList: true, subtree: true });
                    document.addEventListener("DOMContentLoaded", tryLoadScript, { once: true });
                }
                tryLoadScript();
            })();
            """.trimIndent()

    fun registerAtDocumentStart(view: WebView): ScriptHandler? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
        return WebViewCompat.addDocumentStartJavaScript(view, bootstrap, setOf("*"))
    }

    fun setEnabled(view: WebView, enabled: Boolean) {
        val url = view.url ?: return
        if (!BrowserAddress.isWebScheme(Uri.parse(url).scheme)) return

        val script = if (enabled) {
            "if (location.href === ${JSONObject.quote(url)}) { $bootstrap }"
        } else {
            "if (window.__liteSailEruda) window.__liteSailEruda.setEnabled(false);"
        }
        view.evaluateJavascript(script, null)
    }
}
