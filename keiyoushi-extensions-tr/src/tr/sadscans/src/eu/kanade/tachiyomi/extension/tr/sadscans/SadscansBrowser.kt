package eu.kanade.tachiyomi.extension.tr.sadscans

import kotlinx.serialization.json.JsonPrimitive

/** Runs the API request in the same browser identity/session as the site's frontend. */
internal object SadscansBrowser {
    fun script(apiPath: String): String {
        val target = JsonPrimitive(apiPath).toString()
        return """
            (function() {
                if (location.pathname !== '/seriler' || !document.querySelector('main')) return null;
                var key = $target;
                var state = window.__sadscansApi;
                if (!state || state.key !== key) {
                    state = {key: key, pending: true};
                    window.__sadscansApi = state;
                    fetch(key, {credentials: 'same-origin', headers: {'Accept': 'application/json'}})
                        .then(function(response) {
                            return response.text().then(function(body) {
                                state.result = {status: response.status, body: body};
                                state.pending = false;
                            });
                        }).catch(function() {
                            state.result = {status: 0, body: 'SadScans tarayıcı isteği başarısız.'};
                            state.pending = false;
                        });
                }
                return state.pending ? null : JSON.stringify(state.result);
            })();
        """.trimIndent()
    }
}
