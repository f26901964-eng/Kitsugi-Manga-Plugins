function(pos) {
        if (!pos) return '';
        var m = window['_mouseCurve'] || {};
        var e = m['ease'] || '';
        var b = document.body || document.documentElement;
        var f = (typeof $ === 'function' && typeof $.css === 'function') ? ($.css(b, 'flow') || '') : '';
        if (!f && typeof window.getComputedStyle === 'function') {
            var prop = '\x2d\x2dui\x2dflow';
            f = (window.getComputedStyle(b).getPropertyValue(prop) || '').trim();
        }
        var h = 0x811c9dc5, s = pos + '|' + e + '\x23' + f + 'k9\x21';
        for (var i = 0; i < s.length; i++) {
            h ^= s.charCodeAt(i);
            h = (Math.imul(h, 0x01000193) >>> 0);
        }
        return ('00000000' + h.toString(16)).slice(-8);
    }
