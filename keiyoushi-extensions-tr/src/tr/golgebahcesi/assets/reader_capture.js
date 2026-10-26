(bridge, channelName, workerHook) => {
    if (window !== window.top) return;

    const failEarly = (message) => bridge.postMessage("error:" + message);
    if (typeof BroadcastChannel !== "function" || typeof IntersectionObserver !== "function") {
        failEarly("Android System WebView must be updated to read this chapter");
        return;
    }

    // Keep prefetch disabled until the WHOLE capture finishes, not just the first page.
    const overrideKey = "rdr_diag_ovr";
    let previousOverride = null;
    try {
        previousOverride = localStorage.getItem(overrideKey);
        localStorage.setItem(overrideKey, JSON.stringify({ prefetch: false }));
    } catch (_) {
        failEarly("Reader storage is unavailable");
        return;
    }

    window.Blob = new Proxy(window.Blob, {
        construct(target, args) {
            const [parts, options] = args;
            if (Array.isArray(parts) && typeof parts[0] === "string" && /javascript/.test((options && options.type) || "")) {
                return Reflect.construct(target, [[workerHook, ...parts], options]);
            }
            return Reflect.construct(target, args);
        },
    });

    const observed = [];
    window.IntersectionObserver = new Proxy(window.IntersectionObserver, {
        construct(target, args) {
            const observer = Reflect.construct(target, args);
            const callback = args[0];
            observer.observe = new Proxy(observer.observe, {
                apply(observe, thisArg, observeArgs) {
                    observed.push({ element: observeArgs[0], callback, observer });
                    return Reflect.apply(observe, thisArg, observeArgs);
                },
            });
            return observer;
        },
    });
    const setVisible = (page, isIntersecting) => {
        if (!page) return;
        for (const { element, callback, observer } of observed) {
            if (page.contains(element)) callback([{ target: element, isIntersecting }], observer);
        }
    };

    const channel = new BroadcastChannel(channelName);
    const captured = new Set();
    const pending = new Set();
    let count = 0;
    let stopped = false;
    let timer;
    let first = -1;
    let since = 0;
    let visible = -1;
    let lastVisibleAt = 0;
    let challengeSince = null;

    const cleanup = () => {
        stopped = true;
        clearInterval(timer);
        channel.close();
        try {
            if (previousOverride === null) localStorage.removeItem(overrideKey);
            else localStorage.setItem(overrideKey, previousOverride);
        } catch (_) { /* The WebView is about to be destroyed. */ }
    };
    const fail = (message) => {
        if (stopped) return;
        cleanup();
        bridge.postMessage("error:" + message);
    };

    channel.onmessage = ({ data }) => {
        if (stopped || !data || typeof data !== "object") return;
        if (data.error) {
            fail(String(data.error));
            return;
        }
        if (Object.prototype.hasOwnProperty.call(data, "count")) {
            if (!Number.isInteger(data.count) || data.count < 1 || data.count > 2000) {
                fail("Invalid or empty reader page count");
                return;
            }
            if (count && count !== data.count) {
                fail("Reader page count changed during capture");
                return;
            }
            if (!count) bridge.postMessage("count:" + data.count);
            count = data.count;
            return;
        }
        if (!Number.isInteger(data.index) || data.index < 0 || data.index >= count || !(data.image instanceof Blob)) {
            fail("Invalid reader page");
            return;
        }
        if (captured.has(data.index) || pending.has(data.index)) return;
        pending.add(data.index);
        const reader = new FileReader();
        reader.onerror = () => fail("Could not read page " + (data.index + 1));
        reader.onload = () => {
            if (stopped) return;
            const result = reader.result;
            if (typeof result !== "string" || !/^data:[^,]*;base64,/.test(result)) {
                fail("Invalid image data for page " + (data.index + 1));
                return;
            }
            pending.delete(data.index);
            captured.add(data.index);
            bridge.postMessage("page:" + data.index + "," + result);
            if (captured.size === count) cleanup();
        };
        reader.readAsDataURL(data.image);
    };

    timer = setInterval(() => {
        if (stopped) return;
        // Inspect ONLY rendered text. Merely downloading a challenge script does not mean
        // a challenge failed. Allow automatic verification time before reporting the gate.
        const text = document.body?.innerText || "";
        if (text.includes("Güvenlik Doğrulaması")) {
            if (challengeSince === null) challengeSince = Date.now();
            if (Date.now() - challengeSince >= 15000) {
                fail("site_verification_required");
                return;
            }
        } else {
            challengeSince = null;
        }
        if (text.includes("Bu bölüm bu tarayıcıda görüntülenemedi")) {
            fail("site_reader_environment_rejected");
            return;
        }
        if (!count) return;
        let wanted = 0;
        while (wanted < count && captured.has(wanted)) wanted++;
        if (wanted === count) return;
        const now = Date.now();
        if (wanted !== first) {
            first = wanted;
            since = now;
        }
        if (now - since > 30000) {
            fail("Page " + (first + 1) + " did not load");
            return;
        }

        const pages = document.querySelectorAll("[data-reader-page]");
        // Count can arrive before React has mounted the reader. Wait instead of failing immediately.
        if (!pages[wanted]) return;
        if (visible !== wanted) {
            setVisible(pages[visible], false);
            visible = wanted;
        } else if (now - lastVisibleAt < 10000) {
            return;
        }
        // Decode one page at a time: equal-sized encrypted images must not swap panels.
        setVisible(pages[wanted], true);
        lastVisibleAt = now;
    }, 250);
}
