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
                if (location.href !== ${JSONObject.quote(url)}) return;
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

                let script;
                function deactivate() {
                    setEnabled(false);
                }
                function discard() {
                    if (script) script.remove();
                    window.removeEventListener("load", loadScript);
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
                    setEnabled(enabled);
                }

                function loadScript() {
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
                        document.head.appendChild(script);
                    } catch (error) {
                        discard();
                    }
                }

                window.__liteSailEruda = { setEnabled };
                window.addEventListener("pagehide", deactivate);
                if (document.readyState === "complete") {
                    loadScript();
                } else {
                    window.addEventListener("load", loadScript, { once: true });
                }
            })();
            """.trimIndent(),
            null
        )
    }
}
