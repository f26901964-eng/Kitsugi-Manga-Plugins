// Attach session-bound security headers only to this comment module's endpoints.
(function () {
    var script = document.querySelector('script[data-comment-csrf]');
    window.commentCsrfToken = script ? script.getAttribute('data-comment-csrf') : window.commentCsrfToken;
    if (window.commentSecurityInstalled) return;
    window.commentSecurityInstalled = true;
    $.ajaxPrefilter(function (options, original, xhr) {
        var url = new URL(options.url, window.location.href);
        var writes = /\/(savecomment|savemessage|update_comment|update_message|delete_comment|delete_message|save_reaction|report_message|preview_comment|preview_message)\.php$/;
        if (url.origin === window.location.origin && writes.test(url.pathname) && (options.type || 'GET').toUpperCase() === 'POST') {
            xhr.setRequestHeader('X-Comment-CSRF', window.commentCsrfToken || '');
            xhr.setRequestHeader('X-Comment-Antispam', window.commentAntispamToken || '');
        }
    });
})();

/* ==========================================================================



   BUNDLED: AntiSpam Frontend + Yorum Ä°Ã§erik Sistemi



   ========================================================================== */







/**



 * AntiSpam Frontend - Bot Tespit ve Analitik Sistemi



 * 



 * Bu modÃ¼l kullanÄ±cÄ± davranÄ±ÅlarÄ±nÄ± analiz ederek bot tespiti yapar:



 * - Mouse hareketleri



 * - Klavye dinamiÄi (yazma hÄ±zÄ±, pause sÃ¼releri)



 * - Form doldurma sÃ¼resi



 * - Touch events (mobil)



 * - Scroll davranÄ±ÅÄ±



 */







(function() {



    'use strict';







    // AntiSpam namespace



    window.AntiSpam = {



        // Durum deÄiÅkenleri



        state: {



            formToken: null,



            formStartTime: null,



            mouseMovements: 0,



            lastMouseMove: 0,



            keystrokes: 0,



            typingStartTime: null,



            typingEndTime: null,



            firstKeyTime: null,



            keyPresses: [],



            touchCount: 0,



            scrollCount: 0,



            focusedTextarea: null,



            isInitialized: false



        },







        /**



         * BaÅlatma - Form token'Ä±nÄ± alÄ±r ve event listener'larÄ± ekler



         */



        init: function() {



            if (this.state.isInitialized) return;



            



            var self = this;



            



            // Textarea'larÄ± bul ve event listener ekle



            document.querySelectorAll('textarea[name="message"], textarea[name="mcomment"]').forEach(function(textarea) {



                self.attachTextareaListeners(textarea);



            });







            // Mouse tracking



            document.addEventListener('mousemove', function(e) {



                self.trackMouseMove(e);



            }, { passive: true });







            // Touch tracking (mobil)



            document.addEventListener('touchmove', function(e) {



                self.state.touchCount++;



            }, { passive: true });







            // Scroll tracking



            document.addEventListener('scroll', function(e) {



                self.state.scrollCount++;



            }, { passive: true });







            // Form submit Ã¶ncesi verileri topla



            document.querySelectorAll('form').forEach(function(form) {



                // Sadece POST formlarÄ±na antispam uygula (Arama gibi GET formlarÄ±nÄ± atla)



                if (form.method && form.method.toLowerCase() === 'get') {



                    return;



                }



                form.addEventListener('submit', function(e) {



                    self.prepareFormData(form);



                }, true);



            });







            this.state.isInitialized = true;



        },







        /**



         * Textarea event listener'larÄ± ekle



         */



        attachTextareaListeners: function(textarea) {



            var self = this;







            textarea.addEventListener('focus', function(e) {



                self.state.focusedTextarea = e.target;



                // Form baÅlangÄ±Ã§ zamanÄ± (textarea focus + ilk karakter)



                if (!self.state.formStartTime) {



                    self.state.formStartTime = Date.now();



                }



            });







            textarea.addEventListener('blur', function(e) {



                if (self.state.focusedTextarea === e.target) {



                    self.state.typingEndTime = Date.now();



                }



            });







            // Keydown - yazma baÅlangÄ±cÄ±nÄ± yakala



            textarea.addEventListener('keydown', function(e) {



                var now = Date.now();



                



                if (!self.state.firstKeyTime) {



                    self.state.firstKeyTime = now;



                    self.state.typingStartTime = now;



                }







                self.state.keystrokes++;







                // TuÅ arasÄ± sÃ¼releri kaydet (keystroke dynamics)



                if (self.state.lastKeyTime) {



                    var timeBetweenKeys = now - self.state.lastKeyTime;



                    self.state.keyPresses.push(timeBetweenKeys);



                    



                    // Sadece son 50 tuÅu tut



                    if (self.state.keyPresses.length > 50) {



                        self.state.keyPresses.shift();



                    }



                }



                self.state.lastKeyTime = now;



            });







            // Keyup - yazma bitiÅini yakala



            textarea.addEventListener('keyup', function(e) {



                self.state.typingEndTime = Date.now();



            });







            // Input event - her yazma iÃ§in



            textarea.addEventListener('input', function(e) {



                if (!self.state.typingStartTime) {



                    self.state.typingStartTime = Date.now();



                }



            });



        },







        /**



         * Mouse hareketini takip et



         */



        trackMouseMove: function(e) {



            this.state.mouseMovements++;



            this.state.lastMouseMove = Date.now();



        },







        /**



         * Form gÃ¶nderilmeden Ã¶nce tÃ¼m verileri hazÄ±rla



         */



        prepareFormData: function(form) {



            var self = this;



            var now = Date.now();







            // Typing end time'Ä± gÃ¼ncelle



            if (this.state.focusedTextarea && document.activeElement === this.state.focusedTextarea) {



                this.state.typingEndTime = now;



            }







            // Hidden input oluÅtur veya gÃ¼ncelle



            var tokenInput = form.querySelector('input[name="antispam_token"]');



            if (!tokenInput) {



                tokenInput = document.createElement('input');



                tokenInput.type = 'hidden';



                tokenInput.name = 'antispam_token';



                form.appendChild(tokenInput);



            }







            // Mouse data input



            var mouseInput = form.querySelector('input[name="antispam_mouse"]');



            if (!mouseInput) {



                mouseInput = document.createElement('input');



                mouseInput.type = 'hidden';



                mouseInput.name = 'antispam_mouse';



                form.appendChild(mouseInput);



            }







            // Keystroke data input



            var keystrokeInput = form.querySelector('input[name="antispam_keystroke"]');



            if (!keystrokeInput) {



                keystrokeInput = document.createElement('input');



                keystrokeInput.type = 'hidden';



                keystrokeInput.name = 'antispam_keystroke';



                form.appendChild(keystrokeInput);



            }







            // Token deÄerini gÃ¼ncelle



            tokenInput.value = this.state.formToken || '';







            // Mouse verisi



            mouseInput.value = JSON.stringify({



                moves: this.state.mouseMovements,



                lastMove: this.state.lastMouseMove,



                touchCount: this.state.touchCount,



                scrollCount: this.state.scrollCount



            });







            // Keystroke verisi



            var typingDuration = 0;



            if (this.state.typingStartTime && this.state.typingEndTime) {



                typingDuration = (this.state.typingEndTime - this.state.typingStartTime) / 1000;



            }







            keystrokeInput.value = JSON.stringify({



                count: this.state.keystrokes,



                startTime: this.state.typingStartTime ? this.state.typingStartTime / 1000 : 0,



                endTime: this.state.typingEndTime ? this.state.typingEndTime / 1000 : 0,



                duration: typingDuration,



                keyPressIntervals: this.state.keyPresses.slice(-20) // Son 20 tuÅ arasÄ± sÃ¼re



            });







            // Form sÃ¼resini hesapla



            var formDuration = 0;



            if (this.state.formStartTime) {



                formDuration = (now - this.state.formStartTime) / 1000;



            }







            // Ek form data input



            var formDataInput = form.querySelector('input[name="antispam_formdata"]');



            if (!formDataInput) {



                formDataInput = document.createElement('input');



                formDataInput.type = 'hidden';



                formDataInput.name = 'antispam_formdata';



                form.appendChild(formDataInput);



            }







            formDataInput.value = JSON.stringify({



                formDuration: formDuration,



                timestamp: now / 1000,



                sessionId: this.getSessionId()



            });



        },







        /**



         * Session ID oluÅtur/al



         */



        getSessionId: function() {



            var key = 'antispam_sid';



            var sid = sessionStorage.getItem(key);



            if (!sid) {



                sid = 's_' + Math.random().toString(36).substr(2, 9) + Date.now().toString(36);



                sessionStorage.setItem(key, sid);



            }



            return sid;



        },







        /**



         * Token oluÅtur (sunucudan alÄ±ndÄ±ktan sonra)



         */



        setToken: function(token) {



            this.state.formToken = token;



            this.state.formStartTime = Date.now();



        },







        /**



         * Durumu sÄ±fÄ±rla (form gÃ¶nderildikten sonra)



         */



        reset: function() {



            this.state.formToken = null;



            this.state.formStartTime = null;



            this.state.mouseMovements = 0;



            this.state.lastMouseMove = 0;



            this.state.keystrokes = 0;



            this.state.typingStartTime = null;



            this.state.typingEndTime = null;



            this.state.firstKeyTime = null;



            this.state.lastKeyTime = null;



            this.state.keyPresses = [];



            this.state.touchCount = 0;



            this.state.scrollCount = 0;



            this.state.focusedTextarea = null;



        },







        /**



         * Analiz verilerini logla (debugging)



         */



        getDebugInfo: function() {



            return {



                mouseMovements: this.state.mouseMovements,



                keystrokes: this.state.keystrokes,



                typingDuration: this.state.typingStartTime && this.state.typingEndTime 



                    ? (this.state.typingEndTime - this.state.typingStartTime) / 1000 



                    : 0,



                formDuration: this.state.formStartTime 



                    ? (Date.now() - this.state.formStartTime) / 1000 



                    : 0,



                avgKeyInterval: this.calculateAvgKeyInterval()



            };



        },







        /**



         * Ortalama tuÅ arasÄ± sÃ¼reyi hesapla



         */



        calculateAvgKeyInterval: function() {



            if (this.state.keyPresses.length === 0) return 0;



            var sum = this.state.keyPresses.reduce(function(a, b) { return a + b; }, 0);



            return sum / this.state.keyPresses.length;



        }



    };







    // Sayfa yÃ¼klendiÄinde baÅlat



    if (document.readyState === 'loading') {



        document.addEventListener('DOMContentLoaded', function() {



            window.AntiSpam.init();



        });



    } else {



        window.AntiSpam.init();



    }







})();











/* ==== MTR MODAL SYSTEM ==== */



var currentMaxZIndex = 200000;







function openModal(modal) {



    if (!modal) return;



    document.body.appendChild(modal);



    currentMaxZIndex += 20;



    modal.style.setProperty('z-index', currentMaxZIndex, 'important');



    var overlay = modal.querySelector('.mtr-modal__overlay');



    if (overlay) {



        overlay.style.setProperty('z-index', currentMaxZIndex - 1, 'important');



    }



    var dialog = modal.querySelector('.mtr-modal__dialog, .modal-dialog');



    if (dialog) {



        dialog.style.setProperty('z-index', currentMaxZIndex, 'important');



    }



    modal.removeAttribute('inert');



    modal.setAttribute('aria-hidden', 'false');



    modal.classList.add('is-active');



    document.body.classList.add('modal-open');



    document.body.style.overflow = 'hidden';



}







function closeModal(modal) {



    if (!modal) return;



    if (modal.contains(document.activeElement)) {



        document.activeElement.blur();



    }



    modal.classList.remove('is-active', 'show', 'in');



    modal.setAttribute('aria-hidden', 'true');



    modal.setAttribute('inert', '');







    // AÃ§Ä±k baÅka modal var mÄ± kontrol et



    var otherModals = document.querySelectorAll('.mtr-modal.is-active, .modal.show, .modal.in');



    var stillOpen = false;



    for (var i = 0; i < otherModals.length; i++) {



        if (otherModals[i] !== modal) {



            stillOpen = true;



            break;



        }



    }







    if (!stillOpen) {



        document.body.style.overflow = '';



        document.documentElement.style.overflow = '';



        document.body.classList.remove('modal-open');



        document.documentElement.classList.remove('modal-open');



        if (window.jQuery) {



            $('.modal-backdrop').remove();



        }



    }



}



window.openModal = openModal;



window.closeModal = closeModal;







// Event delegation for closing modals



if (!window.__mtrModalListenersAttached) {



    window.__mtrModalListenersAttached = true;



    document.addEventListener('click', function (e) {



        var target = e.target;







        // Click on overlay closes the modal



        if (target.classList.contains('mtr-modal__overlay')) {



            closeModal(target.closest('.mtr-modal'));



            return;



        }







        // Click on close button (X) or cancel button



        if (target.closest('.mtr-modal__close') || target.closest('.btn-cancel')) {



            var modal = target.closest('.mtr-modal');



            closeModal(modal);



            return;



        }







        // Click directly on the mtr-modal backdrop (fallback)



        if (target.classList.contains('mtr-modal')) {



            closeModal(target);



            return;



        }



    });







    document.addEventListener('keydown', function (e) {



        if (e.key === 'Escape') {



            document.querySelectorAll('.mtr-modal.is-active')



                .forEach(function (m) { closeModal(m); });



        }



    });



}







// main.js







// --- AntiSpam Bot Koruma Sistemi ---



// Sayfa yÃ¼klendiÄinde AntiSpam baÅlatÄ±lÄ±r (antispam-frontend.js)



// Token sunucudan alÄ±nÄ±r ve formlara eklenir



function initAntiSpamTokens() {



    // AntiSpam token'Ä± sunucudan al



    fetch('yorum/get_antispam_token.php', {



        method: 'POST',



        credentials: 'same-origin'



    })



    .then(function(response) { return response.json(); })



    .then(function(data) {



        if (data.csrf_token) window.commentCsrfToken = data.csrf_token;
        if (data.token) {
            window.commentAntispamToken = data.token;



            if (window.AntiSpam && typeof window.AntiSpam.setToken === 'function') {



                window.AntiSpam.setToken(data.token);



            }



            // TÃ¼m formlara token ekle



            document.querySelectorAll('form').forEach(function(form) {



                // Arama gibi GET formlarÄ±na antispam token ekleme



                if (form.method && form.method.toLowerCase() === 'get') {



                    return;



                }



                var existingToken = form.querySelector('input[name="antispam_token"]');



                if (!existingToken) {



                    var tokenInput = document.createElement('input');



                    tokenInput.type = 'hidden';



                    tokenInput.name = 'antispam_token';



                    tokenInput.value = data.token;



                    form.appendChild(tokenInput);



                } else {



                    existingToken.value = data.token;



                }



            });



        }



    })



    .catch(function(err) {



        console.error('AntiSpam token alÄ±namadÄ±:', err);



    });



}







// Sayfa yÃ¼klendiÄinde token al



if (document.readyState === 'loading') {



    document.addEventListener('DOMContentLoaded', initAntiSpamTokens);



} else {



    initAntiSpamTokens();



}







// YanÄ±t formlarÄ± aÃ§Ä±ldÄ±ÄÄ±nda token yenile



function refreshAntiSpamToken() {



    initAntiSpamTokens();



}







function getModalApi(modalSelector) {



    var modalElement = typeof modalSelector === 'string' ? document.querySelector(modalSelector) : modalSelector;



    return {



        element: modalElement,



        show: function(trigger) {



            if (!modalElement) return;



            if (window.bootstrap && window.bootstrap.Modal) {



                window.bootstrap.Modal.getOrCreateInstance(modalElement).show(trigger || null);



            } else if (window.openMtrModal) {



                window.openMtrModal(modalElement, trigger || null);



            } else {



                modalElement.classList.add('is-active');



            }



        },



        hide: function() {



            if (!modalElement) return;



            if (window.bootstrap && window.bootstrap.Modal) {



                var instance = window.bootstrap.Modal.getInstance(modalElement) || window.bootstrap.Modal.getOrCreateInstance(modalElement);



                instance.hide();



            } else if (window.closeMtrModal) {



                window.closeMtrModal(modalElement);



            } else {



                modalElement.classList.remove('is-active');



            }



        }



    };



}







function bindModalPrefill(triggerSelector, modalSelector, idKey) {



    $(document).on('click', triggerSelector, function (event) {



        event.preventDefault();



        var $button = $(this);



        var id = $button.data(idKey);



        var modalApi = getModalApi(modalSelector);



        var $modal = $(modalApi.element);



        if (!id || !$modal.length) {



            return;



        }







        $.ajax({



            url: idKey === 'comid' ? 'get_comment_data.php' : 'get_message_data.php',



            type: 'GET',



            dataType: 'json',



            data: idKey === 'comid' ? { com_id: id } : { msg_id: id }



        }).done(function (response) {



            if (!response || !response.success) {



                alert('Veriler alÄ±nÄ±rken bir hata oluÅtu: ' + (response && response.error ? response.error : 'Bilinmeyen hata'));



                return;



            }







            if (idKey === 'comid') {



                $modal.find('#editCommentTextarea').val(response.comment || '');



                $modal.find('#editCommentGifUrl').val(response.gif_url || '');



                $modal.find('#editCommentId').val(id);



            } else {



                $modal.find('#editMessageTextarea').val(response.message || '');



                $modal.find('#editMessageGifUrl').val(response.gif_url || '');



                $modal.find('#editMessageId').val(id);



            }







            modalApi.show($button[0]);



            setTimeout(function() {



                var targetId = idKey === 'comid' ? '#editCommentTextarea' : '#editMessageTextarea';



                var targetVal = idKey === 'comid' ? (response.comment || '') : (response.message || '');



                var $tx = $(targetId);



                if (typeof initCommentEditor === 'function') {



                    initCommentEditor($tx, 120);



                    var inst = getEditorInstance($tx);



                    if (inst) {



                        var initialVal = targetVal;



                        if (response.gif_url && targetVal.indexOf(response.gif_url) === -1) {



                            initialVal = (initialVal ? initialVal + '\n' : '') + '[img]' + response.gif_url + '[/img]';



                        }



                        inst.val(initialVal);



                    }



                }



            }, 60);



        }).fail(function (xhr, status, error) {



            console.error('Modal veri yÃ¼kleme hatasÄ±:', xhr, status, error);



            alert('Veriler alÄ±nÄ±rken bir hata oluÅtu.');



        });



    });



}







$(document).ready(function () {







    function checkUserLoggedIn() {



        var wrapperEl = document.getElementById('yorumlar-section-wrapper');



        return wrapperEl ? (wrapperEl.getAttribute('data-is-logged-in') === 'true') : false;



    }



    window.checkUserLoggedIn = checkUserLoggedIn;



    window.isUserLoggedIn = checkUserLoggedIn();







    // --- SCEditor Custom Commands & Locale Setup ---



    function setupSceditorExtensions() {



        if (typeof $ === 'undefined' || !$.sceditor) return false;







        if (!$.sceditor.commands) {



            $.sceditor.commands = {};



        }



        if (!$.sceditor.commands.gif) {



            $.sceditor.commands.gif = {



                exec: function () {



                    var editor = this;



                    window.activeGifEditor = editor;



                    if (typeof window.openGifModal === 'function') {



                        window.openGifModal(null, editor);



                    }



                },



                txtExec: function () {



                    var editor = this;



                    window.activeGifEditor = editor;



                    if (typeof window.openGifModal === 'function') {



                        window.openGifModal(null, editor);



                    }



                },



                tooltip: 'GIF SeÃ§ / Ekle'



            };



        }







        if ($.sceditor.locale && !$.sceditor.locale['tr']) {



            $.sceditor.locale['tr'] = {



                'Bold': 'KalÄ±n',



                'Italic': 'Ä°talik',



                'Underline': 'AltÄ± Ãizili',



                'Strikethrough': 'ÃstÃ¼ Ãizili',



                'Justify': 'Ä°ki Yana Yasla',



                'Align left': 'Sola Hizala',



                'Center': 'Ortala',



                'Align right': 'SaÄa Hizala',



                'Bullet list': 'Madde Ä°Åaretli Liste',



                'Numbered list': 'NumaralÄ± Liste',



                'Font Name': 'YazÄ± Tipi',



                'Font Size': 'YazÄ± Boyutu',



                'Font Color': 'YazÄ± Rengi',



                'Remove Formatting': 'BiÃ§imlendirmeyi Temizle',



                'Insert an image': 'Resim Ekle',



                'Insert a link': 'BaÄlantÄ± Ekle',



                'Unlink': 'BaÄlantÄ±yÄ± KaldÄ±r',



                'Insert a YouTube video': 'YouTube Videosu Ekle',



                'Copy': 'Kopyala',



                'Paste': 'YapÄ±ÅtÄ±r',



                'Code': 'Kod',



                'Quote': 'AlÄ±ntÄ±',



                'View source': 'Kod / Kaynak GÃ¶rÃ¼nÃ¼mÃ¼',



                'Maximize': 'Tam Ekran',



                'Description (optional):': 'AÃ§Ä±klama (isteÄe baÄlÄ±):',



                'Enter the image URL:': 'Resim baÄlantÄ±sÄ±nÄ± girin:',



                'Enter the URL:': 'BaÄlantÄ±yÄ± girin:',



                'Enter the YouTube video URL or ID:': 'YouTube video baÄlantÄ±sÄ±nÄ± veya ID girin:',



                'Insert': 'Ekle',



                'More': 'Daha Fazla',



                'Close': 'Kapat',



                dateFormat: 'day.month.year'



            };



        }



        return true;



    }



    setupSceditorExtensions();







    // --- SCEditor Initialization & Helper for Comment System ---



    var commentEditorConfig = {



        plugins: 'bbcode',



        height: 285,



        width: '100%',



        toolbar: 'bold,italic,underline,strike,justify|left,center,right,bulletlist,orderedlist|font,color,size,removeformat|image,gif,link,unlink,youtube,copy,paste,code,quote|source,maximize',



        style: '/app/manga/themes/default/assets/minified/jquery.sceditor.default.min.css?v=0111-05',



        emoticonsEnabled: false,



        autoUpdate: true,



        resizeEnabled: true,



        resizeWidth: false,



        resizeMinHeight: 200,



        resizeMaxHeight: 5000,



        readOnly: false,



        startInSourceMode: false,



        runWithoutWysiwygSupport: true,



        locale: 'tr',



        format: 'bbcode'



    };







    function initCommentEditor($textarea, customHeight) {



        if (!$textarea || !$textarea.length) return null;



        if (typeof $textarea.sceditor !== 'function') return null;







        setupSceditorExtensions();







        var existing = $textarea.sceditor('instance');



        if (existing) {



            if (typeof existing.readOnly === 'function') {



                existing.readOnly(false);



            }



            return existing;



        }







        // Kritik: GÃ¶rÃ¼nmeyen (display:none tab veya form) elemanlarda iframe bozuk oluÅmasÄ±n diye bekle



        var isModalActive = $textarea.closest('.mtr-modal.is-active, .modal.show, .modal.in').length > 0;



        if (!$textarea.is(':visible') && !isModalActive) {



            return null;



        }







        var cfg = $.extend({}, commentEditorConfig);



        if (customHeight) {



            cfg.height = customHeight;



        }



        $textarea.sceditor(cfg);



        var inst = $textarea.sceditor('instance');



        if (inst) {



            if (typeof inst.readOnly === 'function') {



                inst.readOnly(false);



            }



            // YanlÄ±ÅlÄ±kla sourceMode'da baÅlamÄ±Åsa wysiwyg moduna geÃ§ir



            if (inst.inSourceMode && inst.inSourceMode()) {



                inst.toggleSourceMode();



            }



            var $c = $textarea.next(".sceditor-container");



            if ($c.length) {



                $c.addClass("comment-sceditor");

                if (!inst.inSourceMode || !inst.inSourceMode()) {
                    $c.find("> textarea").hide();
                    $c.find("> iframe").show();
                }



            }



            var iframeTargetH = customHeight || 200;



            var toolbarH = ($c && $c.length) ? ($c.find(".sceditor-toolbar").outerHeight(true) || 82) : 82;



            var totalTargetH = iframeTargetH + toolbarH;



            if (typeof inst.dimensions === "function") {



                inst.dimensions(null, totalTargetH);



                if ($c && $c.length) {



                    $c.find("iframe, textarea").height(iframeTargetH);



                }



            }



            var $body = inst.getBody ? inst.getBody() : null;



            if ($body && $body.length) {



                $body.attr('contenteditable', 'true');



                $body.on('click focus', function() {



                    inst.focus();



                });



            }



        }



        return inst;



    }



    window.initCommentEditor = initCommentEditor;







    function getEditorInstance($textarea) {



        if (!$textarea || !$textarea.length) return null;



        if (typeof $textarea.sceditor !== 'function') return null;



        return $textarea.sceditor('instance') || null;



    }



    window.getEditorInstance = getEditorInstance;







    function syncEditorContent($textarea) {



        var inst = getEditorInstance($textarea);



        if (inst) {



            inst.updateOriginal();



            return inst.val();



        }



        return ($textarea && $textarea.length) ? ($textarea.val() || '') : '';



    }



    window.syncEditorContent = syncEditorContent;







    function resetEditorContent($textarea) {



        var inst = getEditorInstance($textarea);



        if (inst) {



            inst.val('');



        }



        if ($textarea && $textarea.length) {



            $textarea.val('');



        }



    }



    window.resetEditorContent = resetEditorContent;







    // Backward compatibility stub



    window.ensureToolbarAndEmoji = function() {};







    // Ana yorum editÃ¶rÃ¼nÃ¼ gÃ¼venli bir Åekilde (kÃ¼tÃ¼phane yÃ¼klendiÄinde ve alan gÃ¶rÃ¼nÃ¼r olduÄunda) baÅlat



    function tryInitMainEditor() {



        var $mainTx = $('#message_textarea');



        if (!$mainTx.length) return null;



        var inst = getEditorInstance($mainTx);



        if (inst) return inst;



        if (typeof $mainTx.sceditor !== 'function') return null;



        if ($mainTx.is(':visible')) {



            return initCommentEditor($mainTx, 120);



        }



        return null;



    }



    window.tryInitMainEditor = tryInitMainEditor;







    // 1. KÃ¼tÃ¼phane yÃ¼klenmesini ve gÃ¶rÃ¼nÃ¼rlÃ¼ÄÃ¼ sÃ¼rekli kontrol eden akÄ±llÄ± sayaÃ§ (Script race condition Ã¶nleyici)



    var pollCount = 0;



    var initPollInterval = setInterval(function() {



        pollCount++;



        setupSceditorExtensions();



        var inst = tryInitMainEditor();



        if (inst || pollCount >= 60) {



            clearInterval(initPollInterval);



        }



    }, 100);







    // 2. Kademeli aÃ§Ä±lÄ±Å denemeleri (Sayfa yÃ¼klenirken doÄrudan Ã§aÄrÄ±lar)



    [0, 50, 150, 300, 600, 1200, 2500].forEach(function(delay) {



        setTimeout(tryInitMainEditor, delay);



    });







    if (document.readyState === 'loading') {



        document.addEventListener('DOMContentLoaded', tryInitMainEditor);



    }



    window.addEventListener('load', tryInitMainEditor);







    // 3. KullanÄ±cÄ± sayfayla ilk etkileÅime geÃ§tiÄinde (scroll, mouse, touch) gÃ¶rÃ¼nÃ¼rse baÅlat



    var onFirstUserInteract = function() {



        if (tryInitMainEditor()) {



            window.removeEventListener('scroll', onFirstUserInteract);



            window.removeEventListener('mousemove', onFirstUserInteract);



            window.removeEventListener('touchstart', onFirstUserInteract);



        }



    };



    window.addEventListener('scroll', onFirstUserInteract, { passive: true });



    window.addEventListener('mousemove', onFirstUserInteract, { passive: true });



    window.addEventListener('touchstart', onFirstUserInteract, { passive: true });







    // 4. Sekme (Tab) deÄiÅimi veya tÄ±klama dinleyicileri: Yorumlar sekmesi aÃ§Ä±ldÄ±ÄÄ±nda gecikmesiz baÅlat



    $(document).on('click', '[data-detail-target="tab2"], a[href="#tab2"], [data-detail-target], .detail-tabs__button', function() {



        [20, 80, 200, 450].forEach(function(delay) {



            setTimeout(tryInitMainEditor, delay);



        });



    });







    // 5. Tab panelindeki CSS class ve gÃ¶rÃ¼nÃ¼rlÃ¼k deÄiÅikliklerini izleyen MutationObserver



    var tab2Panel = document.querySelector('[data-detail-panel="tab2"]');



    if (tab2Panel && window.MutationObserver) {



        var panelObserver = new MutationObserver(function() {



            if ($(tab2Panel).is(':visible')) {



                tryInitMainEditor();



            }



        });



        panelObserver.observe(tab2Panel, { attributes: true, attributeFilter: ['class', 'style'] });



    }







    // 6. IntersectionObserver: Yorum bÃ¶lÃ¼mÃ¼ne 200px yaklaÅÄ±ldÄ±ÄÄ± anda Ã¶nceden hazÄ±rla



    if (window.IntersectionObserver) {



        var mainEditorObserver = new IntersectionObserver(function(entries) {



            entries.forEach(function(entry) {



                if (entry.isIntersecting) {



                    if (tryInitMainEditor()) {



                        mainEditorObserver.disconnect();



                    }



                }



            });



        }, { threshold: [0, 0.1, 0.5], rootMargin: '200px' });







        var targetEl = document.getElementById('message_textarea') ||



                       document.getElementById('message_form') ||



                       document.getElementById('yorumlar-section-wrapper');



        if (targetEl) {



            mainEditorObserver.observe(targetEl);



        }



    }







    // 7. KullanÄ±cÄ± yorum alanÄ±na veya formuna odaklandÄ±ÄÄ±nda/dokunduÄunda anÄ±nda baÅlat



    $(document).on('focus pointerdown mousedown touchstart click', '#message_form, #message_textarea, .message_form, .sceditor-container, .detail-panel[data-detail-panel="tab2"]', function() {



        var $mainTx = $('#message_textarea');



        if ($mainTx.length && $mainTx.is(':visible')) {



            var inst = getEditorInstance($mainTx) || initCommentEditor($mainTx, 120);



            if (inst && typeof inst.focus === 'function' && !inst.inSourceMode()) {



                inst.focus();



            }



        }



    });



    // 8. SCEditor Dropdown (YazÄ± boyutu, renk, font vb.) modal Ã¼zerinde kalmasÄ± iÃ§in dinamik z-index garantisi

    if (window.MutationObserver) {

        var scDropdownObserver = new MutationObserver(function(mutations) {

            mutations.forEach(function(m) {

                m.addedNodes.forEach(function(n) {

                    if (n && n.nodeType === 1 && (n.classList.contains('sceditor-dropdown') || (n.matches && n.matches('.sceditor-dropdown')))) {

                        n.style.setProperty('z-index', '10000001', 'important');

                    }

                });

            });

        });

        if (document.body) {

            scDropdownObserver.observe(document.body, { childList: true });

        } else {

            $(function() {

                scDropdownObserver.observe(document.body, { childList: true });

            });

        }

    }









    bindModalPrefill('.edit_comment', '#editCommentModal', 'comid');



    bindModalPrefill('.edit_message', '#editMessageModal', 'msgid');







    // Cevap formlarÄ± aÃ§Ä±ldÄ±ÄÄ±nda SCEditor yÃ¼kle ve odaklan



    $(document).on('click', '.reply_message', function (e) {



        e.preventDefault();



        var msgId = $(this).data('msgid');



        var $container = $('#reply_form_container_message_' + msgId);



        $container.toggle();



        if ($container.is(':visible')) {



            setTimeout(function() {



                var $tx = $('#reply_message_textarea_' + msgId);



                initCommentEditor($tx, 100);



                var inst = getEditorInstance($tx);



                if (inst) {



                    inst.focus();



                }



            }, 40);



        }



        refreshAntiSpamToken();



    });







    $(document).on('click', '.reply_comment', function (e) {



        e.preventDefault();



        var comId = $(this).data('comid');



        var $container = $('#reply_form_container_' + comId);



        $container.toggle();



        if ($container.is(':visible')) {



            setTimeout(function() {



                var $tx = $('#reply_comment_textarea_' + comId);



                initCommentEditor($tx, 100);



                var inst = getEditorInstance($tx);



                if (inst) {



                    inst.focus();



                }



            }, 40);



        }



        refreshAntiSpamToken();



    });







    // YardÄ±mcÄ±: AlÄ±ntÄ±lanacak temiz metni al



    function getCleanQuoteText($contentContainer) {



        if (!$contentContainer || !$contentContainer.length) return '';



        var selectedText = '';



        if (window.getSelection) {



            var sel = window.getSelection();



            if (sel && sel.rangeCount > 0 && !sel.isCollapsed) {



                var range = sel.getRangeAt(0);



                if ($contentContainer[0] && $contentContainer[0].contains(range.commonAncestorContainer)) {



                    selectedText = sel.toString().trim();



                }



            }



        }



        if (selectedText.length > 0) {



            return selectedText;



        }







        var $clone = $contentContainer.clone();



        $clone.find('blockquote, .comment-quote, .gif-container, script, style').remove();



        return $clone.text().replace(/\s+/g, ' ').trim();



    }







    // AlÄ±ntÄ±yÄ± hedef editÃ¶re/textarea'ya ekle



    function insertQuoteIntoEditor($textarea, author, quoteText) {



        if (!$textarea || !$textarea.length || !quoteText) return;



        var cleanAuthor = (author || '').toString().replace(/[:\s]+$/, '').trim();



        var quoteBbCode = (cleanAuthor ? '[quote=' + cleanAuthor + ']' : '[quote]') + quoteText + '[/quote]\n\n';







        var inst = getEditorInstance($textarea);



        if (inst) {



            var currentVal = inst.val();



            if (currentVal && currentVal.trim().length > 0) {



                inst.val(currentVal.replace(/\s*$/, '') + '\n\n' + quoteBbCode);



            } else {



                inst.val(quoteBbCode);



            }



            inst.focus();



        } else {



            var curVal = $textarea.val();



            if (curVal && curVal.trim().length > 0) {



                $textarea.val(curVal.replace(/\s*$/, '') + '\n\n' + quoteBbCode);



            } else {



                $textarea.val(quoteBbCode);



            }



            $textarea.focus();



        }



    }







    // Mesaj AlÄ±ntÄ±la Butonu



    $(document).on('click', '.quote_message', function (e) {



        e.preventDefault();



        var msgId = $(this).data('msgid');



        var author = $(this).data('author') || $(this).closest('.message_ui, .egg').find('.comment-user').first().text().trim();



        author = (author || '').toString().replace(/[:\s]+$/, '').trim();



        var $content = $('#message_content_' + msgId);



        var quoteText = getCleanQuoteText($content);







        var $container = $('#reply_form_container_message_' + msgId);



        $container.show();



        refreshAntiSpamToken();







        var $textarea = $('#reply_message_textarea_' + msgId);



        if (!$textarea.length) {



            $textarea = $('#message_textarea');



        }



        setTimeout(function() {



            initCommentEditor($textarea, 100);



            insertQuoteIntoEditor($textarea, author, quoteText);



        }, 40);



    });







    // Yorum (Alt YanÄ±t) AlÄ±ntÄ±la Butonu



    $(document).on('click', '.quote_comment', function (e) {



        e.preventDefault();



        var comId = $(this).data('comid');



        var msgId = $(this).data('msgid');



        var author = $(this).data('author') || $(this).closest('.comment_ui, .comment-tree').find('.comment-user').first().text().trim();



        author = (author || '').toString().replace(/[:\s]+$/, '').trim();



        var $content = $('#comment_content_' + comId);



        var quoteText = getCleanQuoteText($content);







        var $container = $('#reply_form_container_' + comId);



        var textareaId = 'reply_comment_textarea_' + comId;







        if ($container.length && $('#' + textareaId).length) {



            $container.show();



            refreshAntiSpamToken();



            var $tx = $('#' + textareaId);



            setTimeout(function() {



                initCommentEditor($tx, 100);



                insertQuoteIntoEditor($tx, author, quoteText);



            }, 40);



        } else if (msgId && $('#reply_form_container_message_' + msgId).length) {



            var $msgContainer = $('#reply_form_container_message_' + msgId);



            $msgContainer.show();



            refreshAntiSpamToken();



            var $txMsg = $('#reply_message_textarea_' + msgId);



            setTimeout(function() {



                initCommentEditor($txMsg, 100);



                insertQuoteIntoEditor($txMsg, author, quoteText);



            }, 40);



        } else {



            var $mainTextarea = $('#message_textarea');



            if ($mainTextarea.length) {



                setTimeout(function() {



                    initCommentEditor($mainTextarea, 120);



                    insertQuoteIntoEditor($mainTextarea, author, quoteText);



                }, 40);



            }



        }



    });







    // Oylama fonksiyonu



    $(document).on('click', '.oyla', function (e) {



        e.preventDefault();



        const $this = $(this);



        const msg_id = $this.data('msgid');



        const com_id = $this.data('comid');



        const oy = $this.data('oy');



        const url = msg_id ? "oy_message.php" : "oy_yorum.php";



        const data = msg_id ? {msg_id: msg_id, oy: oy} : {com_id: com_id, oy: oy};







        if (!checkUserLoggedIn()) {



            alert("Oylama yapmak iÃ§in giriÅ yapmalÄ±sÄ±nÄ±z.");



            return;



        }







        $.ajax({



            type: "POST",



            url: url,



            data: data,



            dataType: "json",



            success: function (response) {



                if (response.success) {



                    const $oylamaButtons = $this.closest('.oylama-buttons'); // Daha hÄ±zlÄ±



                    $oylamaButtons.find('.oy-sayisi-pozitif').text('(' + response.pozitif_oylar + ')');



                    $oylamaButtons.find('.oy-sayisi-negatif').text('(' + response.negatif_oylar + ')');







                    $('.oyla[data-msgid="' + msg_id + '"], .oyla[data-comid="' + com_id + '"]').removeClass('aktif');



                    if (oy === 1 || oy === -1) { // Tek satÄ±rda



                        $this.addClass('aktif');



                    }



                } else {



                    alert(response.error);



                }



            },



            error: function (jqXHR, textStatus, errorThrown) {



                console.error("Oylama hatasÄ±:", textStatus, errorThrown);



                alert("Oylama sÄ±rasÄ±nda bir hata oluÅtu.");



            }



        });



    });







    // Yorum Silme



    $(document).on('click', '.delete_comment', function () {



        const $this = $(this);



        const com_id = $this.data('comid');







        if (confirm("Bu yorumu silmek istediÄinizden emin misiniz?")) {



            $.ajax({



                type: "POST",



                url: "delete_comment.php",



                data: {com_id: com_id},



                dataType: "json",



                success: function (response) {



                    if (response.success) {



                        $('#comment_content_' + com_id).html('<span class="deleted-comment-message">SilinmiÅtir.</span><br><br>');



                        $(`.delete_comment[data-comid="${com_id}"], .edit_comment[data-comid="${com_id}"], .reply_comment[data-comid="${com_id}"]`).remove(); // Daha hÄ±zlÄ±



                        $('#comment_' + com_id + ' .oylama-buttons').remove();



                    } else {



                        alert("Yorum silme hatasÄ±: " + response.error);



                    }



                },



                error: function () {



                    alert("Sunucuyla iletiÅim hatasÄ±.");



                }



            });



        }



    });







    // Mesaj Silme



    $(document).on('click', '.delete_message', function () {



        const $this = $(this);



        const msg_id = $this.data('msgid');







        if (confirm("Bu mesajÄ± silmek istediÄinizden emin misiniz?")) {



            $.ajax({



                type: "POST",



                url: "delete_message.php",



                data: {msg_id: msg_id},



                dataType: "json",



                success: function (response) {



                    if (response.success) {



                        $('#message_content_' + msg_id).html('<span class="deleted-message">SilinmiÅtir.</span>');



                        $(`.delete_message, .edit_message, .reply_message`).filter(`button:visible`).each(function () {



                            if ($(this).data('msgid') === msg_id) {



                                $(this).remove();



                            }



                        });



                        $('#reply_form_container_message_' + msg_id).remove();



                        $('#message_' + msg_id + ' .oylama-buttons').remove();



                    } else {



                        alert("Silme hatasÄ±: " + response.error);



                    }



                },



                error: function () {



                    alert("Sunucuyla iletiÅim hatasÄ±.");



                }



            });



        }



    });











    // Yorum GÃ¼ncelleme



    $("#saveCommentChanges").click(function () {



        if (typeof syncEditorContent === 'function') {



            syncEditorContent($('#editCommentTextarea'));



        }



        const com_id = $('#editCommentId').val();



        const new_comment = $('#editCommentTextarea').val();



        const new_gif_url = $('#editCommentGifUrl').val();







        $.ajax({



            type: "POST",



            url: "update_comment.php",



            data: {



                com_id: com_id,



                comment: new_comment,



                gif_url: new_gif_url



            },



            dataType: "json",



            success: function (response) {



                if (response.success) {



                    let updatedContent = '<span class="comment-user">' + response.member_name + '</span> ' + response.new_comment;



                    if (response.new_gif_url) {



                        updatedContent += ' <div class="gif-container"><img src="' + response.new_gif_url + '" alt="GIF" /></div>';



                    }



                    $('#comment_content_' + com_id).html(updatedContent);



                    getModalApi('#editCommentModal').hide();



                    $('#gif-eski-2').hide(); // Bu ID'yi kontrol edin, HTML'de olmayabilir



                } else {



                    alert("Yorum gÃ¼ncelleme hatasÄ±: " + response.error);



                }



            },



            error: function (jqXHR, textStatus, errorThrown) {



                console.error("AJAX error:", textStatus, errorThrown, jqXHR.responseText);



                alert("Sunucuyla iletiÅim hatasÄ±: " + textStatus + " " + errorThrown);



            }



        });



    });















    // Mesaj GÃ¼ncelleme



    $("#saveMessageChanges").click(function () {



        if (typeof syncEditorContent === 'function') {



            syncEditorContent($('#editMessageTextarea'));



        }



        const msg_id = $('#editMessageId').val();



        const new_message = $('#editMessageTextarea').val();



        const new_gif_url = $('#editMessageGifUrl').val();







        $.ajax({



            type: "POST",



            url: "update_message.php",



            data: {



                msg_id: msg_id,



                message: new_message,



                gif_url: new_gif_url



            },



            dataType: "json",



            success: function (response) {



                if (response.success) {



                    let updatedContent = response.new_message;



                    if (response.new_gif_url) {



                        updatedContent += ' <div class="gif-container"><img src="' + response.new_gif_url + '" alt="GIF" /></div>';



                    }



                    $('#message_content_' + msg_id).html(updatedContent);



                    getModalApi('#editMessageModal').hide();



                    $('#gif-eski-2').hide(); // Bu ID'yi kontrol edin, HTML'de olmayabilir



                } else {



                    alert("GÃ¼ncelleme hatasÄ±: " + response.error);



                }



            },



            error: function (jqXHR, textStatus, errorThrown) {



                console.error("AJAX error:", textStatus, errorThrown, jqXHR.responseText);



                alert("Sunucuyla iletiÅim hatasÄ±: " + textStatus + " " + errorThrown);



            }



        });



    });















    // BBCode Ekleme Fonksiyonu



    function insertBBCode(textareaId, bbcode, url = null) {



        const textarea = document.getElementById(textareaId);



        if (!textarea) {



            console.error("Textarea bulunamadÄ± (ID: " + textareaId + ")");



            return;



        }







        const start = textarea.selectionStart;



        const end = textarea.selectionEnd;



        const selectedText = textarea.value.substring(start, end);







        let replacement = '';







        if (bbcode === 'url') {



            url = url || prompt("LÃ¼tfen URL'yi girin:", "http://") || ''; // Tek satÄ±rda



            if (!url) return;



            replacement = (selectedText != "") ? '[url=' + url + ']' + selectedText + '[/url]' : '[url]' + url + '[/url]';



        } else if (bbcode === 'img') {



            url = url || prompt("LÃ¼tfen Resim URL'sini girin:", "http://") || ''; // Tek satÄ±rda



            if (!url) return;



            replacement = '[img]' + url + '[/img]';



        } else {



            replacement = '[' + bbcode + ']' + selectedText + '[/' + bbcode + ']';



        }







        textarea.value = textarea.value.substring(0, start) + replacement + textarea.value.substring(end);



        textarea.selectionStart = start + replacement.length;



        textarea.selectionEnd = start + replacement.length;



        textarea.focus();



    }







    // BBCode toolbar tÄ±klamalarÄ±



    $(document).on('click', '.bbc-toolbar button', function (event) {



        event.stopPropagation();



        const $this = $(this);



        const bbcode = $this.data('bbcode');



        let textareaId = $this.closest('.bbc-toolbar').data('textarea-id');







        if (!textareaId) {



            textareaId = $this.closest('.bbc-toolbar').siblings('textarea').attr('id');



        }







        if (textareaId) {



            insertBBCode(textareaId, bbcode);



        } else {



            console.error("Textarea ID'si bulunamadÄ±");



        }



    });







    // Cevap Formu Ä°Åleme



    $(document).on('submit', '.reply-form', function (e) {



        e.preventDefault();



        const $form = $(this);



        var $replyTx = $form.find('textarea[name="mcomment"]');



        if (typeof syncEditorContent === 'function') {



            syncEditorContent($replyTx);



        }



        



        // AntiSpam otomatik olarak form verilerini hazÄ±rlar



        // Ek gif_url datasÄ± ekle



        const formData = $form.serialize() + "&gif_url=" + encodeURIComponent($form.find('input[name="gif_url"]').val());







        $.ajax({



            type: 'POST',



            url: $form.attr('action'),



            data: formData,



            dataType: 'json',



            success: function (response) {



                if (response.success) {



                    const parentContainer = $form.closest('.reply-form-container');



                    const parentCommentId = $form.find('input[name="parent_comment_id"]').val();



                    const marginLeft = parentCommentId ? (parseInt(parentContainer.css('margin-left')) - 42) : 42;



                    const buttons =



                        ' <button class="btn-yorum edit_comment" data-comid="' + response.com_id + '" data-toggle="modal" data-target="#editCommentModal">GÃ¼ncelle</button>\n' +



                        ' <button class="btn-yorum delete_comment" data-comid="' + response.com_id + '">Sil</button>';



                    const avatarUrl = response.avatar_url ? response.avatar_url : 'avatar.png';



                    const yeniYorumHTML =



                        '<div class="comment-tree comment-tree-' + response.com_id + '">\n' +



                        '    <div class="comment_ui" id="comment_' + response.com_id + '" style="margin-left: ' + marginLeft + 'px;">\n' +



                        '        <div class="comment_text">\n' +



                        '            <div class="comment_actual_text">\n' +



                        '  <span class="glyphicon glyphicon-share-alt" style="padding:10px;padding-bottom:20px;"></span>\n' +



                        '                <img src="' + avatarUrl + '" alt="KullanÄ±cÄ± AvatarÄ±" width="32" height="32">\n' +



                        '                <div class="comment-content-wrapper">\n' +



                        '                    <span class="comment-user">' + response.member_name + '</span>\n' +



                        '                    <span id="comment_content_' + response.com_id + '">' + response.comment + '</span>\n' +



                        (response.gif_url ? '                    <div class="gif-container"><img src="' + response.gif_url + '" alt="GIF" /></div>' : '') +



                        '                    <div class="button-container">' + buttons + '</div>\n' +



                        '                </div>\n' +



                        '            </div>\n' +



                        '        </div>\n' +



                        '    </div>\n' +



                        '</div>';







                    if (parentCommentId === "") {



                        parentContainer.siblings('.comment-section').prepend(yeniYorumHTML);



                    } else {



                        parentContainer.before(yeniYorumHTML);



                    }







                    // Yorum yapma formunu gizle



                    $form.closest('.reply-form-container').hide();



                    $form.find('input[name="gif_url"]').val('');



                    if (typeof resetEditorContent === 'function') {



                        resetEditorContent($replyTx);



                    } else {



                        $replyTx.val('');



                    }







                    // AÃ§Ä±k modal yoksa body scroll kilidini kesin olarak kaldÄ±r



                    if (!document.querySelector('.mtr-modal.is-active, .modal.show, .modal.in')) {



                        document.body.style.overflow = '';



                        document.documentElement.style.overflow = '';



                        document.body.classList.remove('modal-open');



                        document.documentElement.classList.remove('modal-open');



                    }







                    // AntiSpam reset (form gÃ¶nderildikten sonra)



                    if (window.AntiSpam) {



                        AntiSpam.reset();



                    }







                } else {



                    alert(response.error);



                }



            },



            error: function (jqXHR, textStatus, errorThrown) {



                console.log(jqXHR.responseText);



                console.error("AJAX error:", textStatus, errorThrown);



                alert("Bir hata oluÅtu: " + textStatus + " " + errorThrown);



            }



        });



    });







    // Mesaj Formu Ä°Åleme



    $("#message_form").submit(function (e) {



        e.preventDefault();



        const $form = $(this);



        if (typeof syncEditorContent === 'function') {



            syncEditorContent($("#message_textarea"));



        }



        const message = $("#message_textarea").val() || "";



        const gif_url = $("#gif_url").val() || "";



        const sayfa = $("#sayfa").val() || "";



        const veri = $("#veri").val() || "";



        const name = $("#name").val() || "";



        const honeypot = $("#honeypot").val() || "";







        if (message.trim() === "") {



            alert("LÃ¼tfen bir mesaj girin.");



            return;



        }







        // PHP ile belirlenen kullanÄ±cÄ± giriÅ durumunu kontrol et



        if (!checkUserLoggedIn()) {



            if (name.trim() === "") {



                alert("LÃ¼tfen bir isim girin.");



                return;



            }



        }







        $.ajax({



            type: "POST",



            url: "savemessage.php",



            data: {



                honeypot: honeypot,



                message: message,



                gif_url: gif_url,



                sayfa: sayfa,



                veri: veri,



                name: name



            },



            dataType: "json",



            success: function (response) {



                if (response.success) {



                    const newIndex = $("#message-list li.egg").length;



                    const avatarUrl = response.avatar_url ? response.avatar_url : 'avatar.png';



                    const safeMessage = escapeHtml(response.message);  // HTML'i burada escape et







                    const newMessageHtml =



                        '<li class="egg" style="list-style: none;" id="message_' + response.msg_id + '" data-index="' + newIndex + '">\n' +



                        '    <div class="egg_Body">\n' +



                        '        <h3 class="egg_Message">\n' +



                        '            <img src="' + avatarUrl + '" width="40" height="40"/>\n' +



                        '            <span class="comment-user">' + response.member_name + '</span>\n' +



                        '            <span id="message_content_' + response.msg_id + '">' + response.message + '</span>\n' +



                        (response.gif_url ? '                    <div class="gif-container"><img src="' + response.gif_url + '" alt="GIF" /></div>' : '') +



                        '            <div class="button-container" style="margin-top: 40px;">\n' +



                        '                <button class="btn-yorum edit_message" data-msgid="' + response.msg_id + '" data-toggle="modal" data-target="#editMessageModal">GÃ¼ncelle</button>\n' +



                        '                <button class="btn-yorum delete_message" data-msgid="' + response.msg_id + '">Sil</button>\n' +



                        '            </div>\n' +



                        '        </h3>\n' +



                        '        <div class="comment-section"></div>\n' +



                        '    </div>\n' +



                        '</li>';







                    $("#message-list").prepend(newMessageHtml);



                    if (typeof resetEditorContent === 'function') {



                        resetEditorContent($("#message_textarea"));



                    }



                    $form.find("#message_textarea, #gif_url, #name, #honeypot").val("");







                    // AÃ§Ä±k modal yoksa body scroll kilidini kesin olarak kaldÄ±r



                    if (!document.querySelector('.mtr-modal.is-active, .modal.show, .modal.in')) {



                        document.body.style.overflow = '';



                        document.documentElement.style.overflow = '';



                        document.body.classList.remove('modal-open');



                        document.documentElement.classList.remove('modal-open');



                    }







                    // AntiSpam reset (form gÃ¶nderildikten sonra)



                    if (window.AntiSpam) {



                        AntiSpam.reset();



                    }



                    $("#message_" + response.msg_id).show();



                } else {



                    alert("Mesaj eklenirken bir hata oluÅtu: " + response.error);



                }



            },



            error: function () {



                alert("Sunucuyla iletiÅim hatasÄ±.");



            }



        });



    });







    // HTML Entity Encoding fonksiyonu (en Ã¼stte tanÄ±mlanmalÄ±)



    function escapeHtml(text) {



        const div = document.createElement('div');



        div.textContent = text;



        return div.innerHTML;



    }







    $("#message-list li.egg").each(function () {



        if ($(this).data("index") > 19) {



            $(this).hide();



        }



    });







    // Daha Fazla Mesaj YÃ¼kle



    $("#load-more-messages").click(function () {



        $("#message-list li.egg:hidden").slice(0, 20).show();



        visibleCount += 20;







        if (visibleCount >= $("#message-list li.egg").length) {



            $("#load-more-messages").hide();



        }



    });







    let visibleCount = 20;







    // GIF iÅlemleri



    const apiKey = "nBtkdj4iZA7jo78rhNu1eFZW2zAb04Nq"; // Giphy API AnahtarÄ±nÄ±zÄ± buraya koymalÄ±sÄ±nÄ±z.



    let currentInput = null;



    let next = 0; // Giphy iÃ§in offset tabanlÄ± sayfalama (0'dan baÅlar)



    let isLoading = false;







    // GIF modalini aÃ§ma



    $(document).on('click', '.gif-select-button', function () {



        currentInput = $(this).prev('input[name="gif_url"], #editCommentGifUrl, #editMessageGifUrl');



        var targetInputId = currentInput && currentInput.length ? currentInput.attr('id') : '';



        if (typeof openGifModal === 'function') {



            openGifModal(targetInputId);



        } else {



            openModal(document.getElementById('gifModal'));



        }



    });







    // GIF modal aÃ§Ä±ldÄ±ÄÄ±nda



    $('#gifModal').on('shown.bs.modal', function () {



        next = 0;



        loadTrendingGifs();



        $('#gif-search-modal').focus();



    });







    // GIF arama butonu



    $("#search-button-modal").click(function () {



        next = 0;



        searchGifs();



    });







    // GIF arama (enter tuÅu)



    $("#gif-search-modal").keypress(function (event) {



        if (event.which == 13) {



            next = 0;



            searchGifs();



            event.preventDefault();



        }



    });







    // GIF arama fonksiyonu



    function searchGifs() {



        const searchTerm = $("#gif-search-modal").val();



        if (searchTerm.trim() === "") {



            return;



        }



        const offset = next || 0;



        const url = "https://api.giphy.com/v1/gifs/search?api_key=" + apiKey + "&q=" + encodeURIComponent(searchTerm) + "&limit=15&offset=" + offset;



        fetchGifs(url);



    }







    // Trend GIF'leri yÃ¼kle



    function loadTrendingGifs() {



        const offset = next || 0;



        const url = "https://api.giphy.com/v1/gifs/trending?api_key=" + apiKey + "&limit=15&offset=" + offset;



        fetchGifs(url);



    }



    window.loadTrendingGifs = loadTrendingGifs;



    window.searchGifs = searchGifs;







    // GIF'leri Ã§ekme (ortak fonksiyon)



    function fetchGifs(url) {



        if (isLoading) return;



        isLoading = true;



        $("#loading-indicator").show();



        if (next === 0 || next === null) {



             $("#gif-results").empty();



        }



        $.ajax({



            url: url,



            method: "GET",



            success: function(response) {



                // Sonraki sayfa offset deÄeri



                next = response.pagination.offset + response.pagination.count;



                displayGifs(response.data);



                $("#loading-indicator").hide();



                isLoading = false;



            },



           error: function(error) {



                console.error("Giphy API error:", error);



                $("#gif-results").html("<p>GIFs could not be loaded.</p>");



                $("#loading-indicator").hide();



                isLoading = false;



            }



        });



    }







    // GIF'leri gÃ¶rÃ¼ntÃ¼le



    function displayGifs(gifs) {



        if ((!gifs || gifs.length === 0) && (next === 0 || next === 15)) {



            $("#gif-results").html("<p>No results found.</p>");



            return;



        }



        gifs.forEach(gif => {



            const rawGifUrl = gif.images.fixed_height.url;



            const gifUrl = (rawGifUrl || '').split('?')[0]; // Temiz .gif URL formatÄ±



            const imgElement = $('<img src="' + gifUrl + '" alt="GIF" class="gif_tenor" style="cursor:pointer; margin:5px; height:120px; object-fit:cover;">');



            imgElement.click(function() {



                var modal = document.getElementById('gifModal');



                var target = null;



                if (modal && modal.currentTargetInput) {



                    var el = document.getElementById(modal.currentTargetInput);



                    if (el) {



                        target = $(el);



                    }



                }



                if (!target && currentInput && currentInput.length) {



                    target = currentInput;



                }



                if (target && target.length) {



                    target.val(gifUrl).trigger('change').trigger('input');



                }







                // SCEditor Entegrasyonu: Aktif editÃ¶re GIF doÄrudan eklenir ve anÄ±nda hareketli olarak gÃ¶rÃ¼nÃ¼r



                var editor = window.activeGifEditor;



                if (!editor && target && target.length) {



                    var $form = target.closest('form, .mtr-modal__dialog, .comment_ui, #tab1, #tab2');



                    var $tx = $form.find('textarea.sceditor-attached, textarea#message_textarea, textarea.edit_comment_textarea, textarea#editCommentTextarea, textarea#editMessageTextarea');



                    if ($tx.length && typeof $tx.sceditor === 'function') {



                        editor = $tx.sceditor('instance');



                    }



                }







                if (editor) {



                    if (typeof editor.inSourceMode === 'function' && editor.inSourceMode()) {



                        editor.insertText('[img]' + gifUrl + '[/img]');



                    } else {



                        // WYSIWYG modunda GIF'i canlÄ± ve hareketli olarak editÃ¶rÃ¼n iÃ§ine ekler



                        if (typeof editor.wysiwygEditorInsertHtml === 'function') {



                            editor.wysiwygEditorInsertHtml('<img src="' + gifUrl + '" alt="GIF" />');



                        } else if (typeof editor.insert === 'function') {



                            editor.insert('[img]' + gifUrl + '[/img]');



                        }



                    }



                    if (typeof editor.focus === 'function') {



                        editor.focus();



                    }



                }







                closeModalById('gifModal');



            });



            $("#gif-results").append(imgElement);



        });



    }







    // GIF modal scroll eventi



    $('#gifModal .modal-body, #gifModal .mtr-modal__body').scroll(function() {



        if (next && !isLoading) {



            const $modalBody = $(this);



            if ($modalBody.scrollTop() + $modalBody.innerHeight() >= $modalBody[0].scrollHeight - 100) {



                const searchTerm = $("#gif-search-modal").val();



                if(searchTerm.trim() !== ""){



                   searchGifs();



                } else {



                  loadTrendingGifs();



                }



            }



        }



    });







    // Form submit (DEBUG)



    $("#my-form").submit(function(event) {



                event.preventDefault();



        console.log("Form Submitted!  Check the input values.");



        $('input[name="gif_url"]').each(function() {



            console.log($(this).val());



        });



    });







    // GIF seÃ§ butonu oluÅturma ve ekleme



    function addGifButton(inputElement) {



        return; // SCEditor menÃ¼sÃ¼ne entegre edildiÄi iÃ§in alttaki buton eklenmez



        if ($(inputElement).next('.gif-select-button').length === 0) {



            const $button = $('<button type="button" class="btn-yorum gonder gif-select-button" style="margin: 10px 0 10px 0;">GIF SeÃ§</button>');



            $(inputElement).after($button);



        }



    }







    // GIF seÃ§ butonlarÄ±nÄ± ekle



    $('input[name="gif_url"]').each(function() {



        addGifButton(this);



    });







    $('#editCommentModal').on('shown.bs.modal', function() {



        addGifButton($('#editCommentGifUrl'));



    });



    $('#editMessageModal').on('shown.bs.modal', function() {



        addGifButton($('#editMessageGifUrl'));



    });







    // --- Spoiler TÄ±klama (Ãnizleme ve Yorumlar) ---



    $(document).on('click keydown', '.spoiler', function(event) {



        if ($(event.target).closest('.spoiler')[0] !== this) return;
        if (event.type === 'keydown') {
            if (event.target !== this || (event.key !== 'Enter' && event.key !== ' ')) return;
            event.preventDefault();
        } else if ($(this).hasClass('is-revealed') && $(event.target).closest('a, button, input, select, textarea, video, iframe').length) {
            return;
        }
        var revealed = $(this).toggleClass('is-revealed').hasClass('is-revealed');
        $(this).attr('aria-expanded', revealed ? 'true' : 'false');



    });







            // Åikayet Et modalÄ±



            $(document).on('click', '[data-target="#rapor"], [data-bs-target="#rapor"]', function () {



                var $button = $(this);



                var $modal = $('#rapor');



                $modal.find('#report_message_id').val($button.data('id') || '');



                $modal.find('#report_is_comment').val($button.data('type') === 'comment' ? 1 : 0);



                $modal.find('#report_reason').val('');



                $modal.find('#report_detail').val('');



                $('#submitReportBtn').prop('disabled', true);



            });







            $('#report_reason').on('change', function() {



                $('#submitReportBtn').prop('disabled', $(this).val() === '');



            });







            $('#submitReportBtn').on('click', function() {



                if ($('#report_reason').val() === '') {



                    alert('LÃ¼tfen bir Åikayet nedeni seÃ§iniz.');



                    return;



                }







                // Form verilerini manuel oluÅtur â input name'leri PHP'nin beklediÄi



                // alan adlarÄ±yla eÅleÅmeyebilir, bu yÃ¼zden ID'lerden direkt okuyoruz



                var reportData = {



                    message_id: $('#report_message_id').val() || '',



                    is_comment: $('#report_is_comment').val() || '0',



                    reason:     $('#report_reason').val() || '',



                    detail:     $('#report_detail').val() || '',



                    sayfa:      $('#report_is_sayfa').val() || ''



                };







                $.ajax({



                    url: 'yorum/report_message.php',



                    method: 'POST',



                    data: reportData,



                    dataType: 'json',



                    success: function(resp) {



                        alert(resp.message || resp.error);



                        // Modal'Ä± gÃ¼venli Åekilde kapat



                        var raporModal = document.getElementById('rapor');



                        if (raporModal) {



                            // Ãnce mtr-modal sistemini dene (yorum-icerik.php inline script)



                            if (typeof closeModalById === 'function') {



                                closeModalById('rapor');



                            }



                            // yorum-icerik.js modal sistemi



                            else if (typeof closeModal === 'function') {



                                closeModal(raporModal);



                            }



                            // Fallback



                            else {



                                raporModal.classList.remove('is-active', 'is-open', 'in');



                                raporModal.style.display = 'none';



                                raporModal.setAttribute('aria-hidden', 'true');



                            }



                            // Body scroll kilidini kaldÄ±r (sayfa kitlenmesini Ã¶nle)



                            document.body.style.overflow = '';



                            document.body.classList.remove('modal-open');



                            // Bootstrap 3 backdrop'unu temizle



                            $('.modal-backdrop').remove();



                        }



                    },



                    error: function() {



                        alert('Bilinmeyen bir hata oluÅtu.');



                    }



                });



            });











    $('.btn-tepki').on('click', function(e) {



        e.preventDefault();



        const reaction = $(this).data('reaction');



        const sayfa = $("#sayfa").val();



        const veri = $("#veri").val();



        $.ajax({



            type: 'POST',



            url: 'save_reaction.php',



            data: { reaction: reaction, sayfa: sayfa, veri: veri },



            dataType: 'json',



            success: function(response) {



                if (response && response.success) {



                    $('#total-reactions').text(response.total_reactions + ' Tepki');



                    $('.reaction-count').each(function() {



                        const reactionType = $(this).data('reaction');



                        $(this).text(response.reactions_data && response.reactions_data.hasOwnProperty(reactionType) ? response.reactions_data[`${reactionType}`] : 0);



                    });



                    $('.btn-tepki').removeClass('reacted');



                    if (response.user_reacted) {



                        $(`.btn-tepki[data-reaction="${response.user_reacted}"]`).addClass('reacted');



                    }



                } else {



                    alert('Tepki kaydedilirken bir hata oluÅtu: ' + (response && response.error ? response.error : 'Bilinmeyen bir hata'));



                }



            },



            error: function(jqXHR, textStatus, errorThrown) {



                console.error('Tepki AJAX hatasÄ±:', textStatus, errorThrown);



                alert('Tepki kaydedilirken bir sunucu hatasÄ± oluÅtu.');



            }



        });



    });







    function loadReactions() {



       const sayfa = $("#sayfa").val();



       const veri = $("#veri").val();



       $.ajax({



           type: 'POST',



           url: 'get_reactions.php',



           data: { sayfa: sayfa, veri: veri },



           dataType: 'json',



           success: function(response) {



               if (response && response.success) {



                   $('#total-reactions').text(response.total_reactions + ' Tepki');



                   $('.reaction-count').each(function() {



                       const reactionType = $(this).data('reaction');



                       $(this).text(response.reactions && response.reactions.hasOwnProperty(reactionType) ? response.reactions[`${reactionType}`] : 0);



                   });



                   $('.btn-tepki').removeClass('reacted');



                   if (response.user_reaction) {



                       $(`.btn-tepki`).each(function() {



                           if ($(this).data('reaction') === response.user_reaction) {



                               $(this).addClass('reacted');



                           }



                       });



                   }



               } else {



                   console.error('Tepkiler yÃ¼klenirken bir hata oluÅtu:', response && response.error ? response.error : 'Bilinmeyen bir hata');



               }



           },



           error: function(jqXHR, textStatus, errorThrown) {



               console.error('Tepkiler AJAX hatasÄ±:', textStatus, errorThrown);



           }



       });



    };



    loadReactions();







    // Target comment scroll



    if (typeof window.initialTargetCommentId === 'undefined') {



        var wrapperEl = document.getElementById('yorumlar-section-wrapper');



        window.initialTargetCommentId = wrapperEl ? (wrapperEl.getAttribute('data-target-comment-id') || '') : '';



    }



    var targetCommentId = window.initialTargetCommentId;



    if (targetCommentId) {



        var targetElement = $('#comment_' + targetCommentId);



        if (targetElement.length > 0) {



            $('html, body').animate({



                scrollTop: targetElement.offset().top - 100



            }, 500);



            targetElement.css('background-color', '#704b20');



            setTimeout(function() {



                targetElement.css('background-color', '');



            }, 2000);



        } else {



            console.log("Hedef yorum elementi bulunamadÄ±: comment_" + targetCommentId);



        }



    }



});







/* ==== COMMENT SORTING HANDLER ==== */



document.addEventListener('click', function(e) {



    var btn = e.target.closest('.comment-sort-btn');



    if (!btn) return;



    



    e.preventDefault();



    var sortKey = btn.getAttribute('data-sort');



    var wrapper = document.getElementById('yorumlar-section-wrapper');



    if (!wrapper) return;



    



    wrapper.style.opacity = '0.5';



    wrapper.style.pointerEvents = 'none';







    var postData = {



        sort: sortKey,



        current_page_url: wrapper.getAttribute('data-current-url') || window.location.href



    };







    var chapterId = wrapper.getAttribute('data-chapter-id');



    var mangaId = wrapper.getAttribute('data-manga-id');



    var sayfaId = wrapper.getAttribute('data-sayfa-id');







    if (chapterId) postData.chapter_id = chapterId;



    if (mangaId) postData.manga_id = mangaId;



    if (sayfaId) postData.sayfa_id = sayfaId;







    var targetUrl;



    try {



        var url = new URL(window.location.href);



        if (sortKey) url.searchParams.set('sort', sortKey);



        targetUrl = url.toString();



    } catch(err) {



        targetUrl = btn.getAttribute('href');



    }







    var handleSuccess = function(responseHtml) {



        var parser = new DOMParser();



        var doc = parser.parseFromString(responseHtml, 'text/html');



        var newContent = doc.getElementById('yorumlar-section-wrapper');



        if (newContent) {



            wrapper.innerHTML = newContent.innerHTML;



        } else {



            wrapper.innerHTML = responseHtml;



        }



        wrapper.style.opacity = '1';



        wrapper.style.pointerEvents = 'auto';



        if (window.history && window.history.pushState) {



            window.history.pushState(null, '', targetUrl);



        }



    };







    var handleError = function() {



        wrapper.style.opacity = '1';



        wrapper.style.pointerEvents = 'auto';



        window.location.href = targetUrl;



    };







    if (window.jQuery) {



        $.post('yorum/yorum-icerik.php', postData, handleSuccess).fail(handleError);



    } else {



        var formData = new FormData();



        for (var k in postData) formData.append(k, postData[k]);



        fetch('yorum/yorum-icerik.php', { method: 'POST', body: formData })



        .then(function(r) { return r.text(); })



        .then(handleSuccess)



        .catch(handleError);



    }



});







/* ==== MODAL HELPER & EXTENDED MODAL LOGIC ==== */



function openGifModal(targetInputId, editorInstance) {



    var modal = document.getElementById('gifModal');



    if (modal) {



        modal.currentTargetInput = targetInputId || null;



        if (editorInstance) {



            window.activeGifEditor = editorInstance;



        } else if (targetInputId) {



            var $el = $('#' + targetInputId);



            if ($el.length) {



                var $form = $el.closest('form, .mtr-modal__dialog, .comment_ui, #tab1, #tab2');



                var $tx = $form.find('textarea.sceditor-attached, textarea#message_textarea, textarea.edit_comment_textarea, textarea#editCommentTextarea, textarea#editMessageTextarea');



                if ($tx.length && typeof $tx.sceditor === 'function') {



                    window.activeGifEditor = $tx.sceditor('instance');



                }



            }



        }



        openModal(modal);



        // GIF modal'Ä±n z-index'ini diÄer aÃ§Ä±k modallarÄ±n kesinlikle Ã¼stÃ¼ne Ã§Ä±kar



        currentMaxZIndex += 100;



        modal.style.setProperty('z-index', currentMaxZIndex, 'important');



        var dialog = modal.querySelector('.mtr-modal__dialog');



        if (dialog) {



            dialog.style.setProperty('z-index', currentMaxZIndex, 'important');



            dialog.style.setProperty('position', 'relative', 'important');



        }



        var overlay = modal.querySelector('.mtr-modal__overlay');



        if (overlay) {



            overlay.style.setProperty('z-index', currentMaxZIndex - 1, 'important');



        }



        if (typeof window.loadTrendingGifs === 'function') {



            window.loadTrendingGifs();



        }



        var searchInput = document.getElementById('gif-search-modal');



        if (searchInput) {



            setTimeout(function() { searchInput.focus(); }, 100);



        }



    }



}



window.openGifModal = openGifModal;







(function() {



    var modals = document.querySelectorAll('.mtr-modal');



    for (var i = 0; i < modals.length; i++) {



        document.body.appendChild(modals[i]);



    }



})();







function closeModalById(modalId) {



    var modal = document.getElementById(modalId);



    if (modal) {



        closeModal(modal);



    } else {



        document.body.style.overflow = '';



        document.documentElement.style.overflow = '';



        document.body.classList.remove('modal-open');



        document.documentElement.classList.remove('modal-open');



        if (window.jQuery) {



            $('.modal-backdrop').remove();



        }



    }



    return false;



}



window.closeModalById = closeModalById;







function openModalById(modalId) {



    var modal = document.getElementById(modalId);



    if (modal) {



        if (modal.parentElement !== document.body) {



            document.body.appendChild(modal);



        }



        if (typeof window.ensureToolbarAndEmoji === 'function') {



            window.ensureToolbarAndEmoji('#' + modalId, '');



        }



        openModal(modal);



    }



    return false;



}



window.openModalById = openModalById;







document.addEventListener('click', function(e) {



    var editCommentButton = e.target.closest('.edit_comment');



    var editMessageButton = e.target.closest('.edit_message');



    if (!editCommentButton && !editMessageButton) {



        return;



    }







    e.preventDefault();



    e.stopImmediatePropagation();







    if (editCommentButton) {



        loadEditModal({



            id: editCommentButton.getAttribute('data-comid'),



            url: 'get_comment_data.php',



            data: { com_id: editCommentButton.getAttribute('data-comid') },



            modalId: 'editCommentModal',



            idInput: 'editCommentId',



            textInput: 'editCommentTextarea',



            gifInput: 'editCommentGifUrl',



            textKey: 'comment',



            errorPrefix: 'Yorum'



        });



        return;



    }







    loadEditModal({



        id: editMessageButton.getAttribute('data-msgid'),



        url: 'get_message_data.php',



        data: { msg_id: editMessageButton.getAttribute('data-msgid') },



        modalId: 'editMessageModal',



        idInput: 'editMessageId',



        textInput: 'editMessageTextarea',



        gifInput: 'editMessageGifUrl',



        textKey: 'message',



        errorPrefix: 'Mesaj'



    });



}, true);







function loadEditModal(options) {



    if (!options.id) {



        alert(options.errorPrefix + ' bilgisi alÄ±namadÄ±. LÃ¼tfen sayfayÄ± yenileyip tekrar deneyin.');



        return;



    }







    var idEl = document.getElementById(options.idInput);



    var textEl = document.getElementById(options.textInput);



    var gifEl = document.getElementById(options.gifInput);



    if (idEl) idEl.value = '';



    if (textEl) textEl.value = '';



    if (gifEl) gifEl.value = '';







    $.ajax({



        url: options.url,



        type: 'GET',



        dataType: 'json',



        data: options.data



    }).done(function(response) {



        if (!response || !response.success) {



            alert(options.errorPrefix + ' verileri alÄ±nÄ±rken bir hata oluÅtu: ' + (response && response.error ? response.error : 'Bilinmeyen hata'));



            return;



        }







        var contentVal = response[options.textKey] || '';



        var gifVal = response.gif_url || '';



        if (gifVal && contentVal.indexOf(gifVal) === -1) {



            contentVal = (contentVal ? contentVal + '\n' : '') + '[img]' + gifVal + '[/img]';



        }







        if (textEl) textEl.value = contentVal;



        if (gifEl) gifEl.value = gifVal;



        if (idEl) idEl.value = options.id;







        openModalById(options.modalId);







        // EditÃ¶r baÅlatma ve iÃ§eriÄi tam aktarma



        setTimeout(function() {



            var $tx = $('#' + options.textInput);



            if ($tx.length && typeof initCommentEditor === 'function') {



                var inst = initCommentEditor($tx, 180);



                if (!inst && typeof getEditorInstance === 'function') {



                    inst = getEditorInstance($tx);



                }



                if (inst) {



                    inst.val(contentVal);



                    if (typeof inst.readOnly === 'function') {



                        inst.readOnly(false);



                    }



                    if (inst.inSourceMode && inst.inSourceMode()) {



                        inst.toggleSourceMode();



                    }



                    if (typeof inst.focus === 'function') {



                        inst.focus();



                    }



                }



            }



        }, 60);



    }).fail(function() {



        alert(options.errorPrefix + ' verileri alÄ±nÄ±rken sunucuyla iletiÅim hatasÄ± oluÅtu.');



    });



}



window.loadEditModal = loadEditModal;







if (window.jQuery) {



    $(document).ajaxSuccess(function(event, xhr, settings) {



        var url = settings && settings.url ? settings.url : '';



        var isCommentUpdate = url.indexOf('update_comment.php') !== -1;



        var isMessageUpdate = url.indexOf('update_message.php') !== -1;







        if (!isCommentUpdate && !isMessageUpdate) {



            return;



        }







        var response = xhr.responseJSON;



        if (!response && xhr.responseText) {



            try {



                response = JSON.parse(xhr.responseText);



            } catch (e) {



                return;



            }



        }







        if (!response || !response.success) {



            return;



        }







        closeModalById(isCommentUpdate ? 'editCommentModal' : 'editMessageModal');



    });



}







document.addEventListener('click', function(e){



    var trigger = e.target.closest('[data-target], [data-bs-target]');



    if (trigger) {



        if (trigger.closest('.edit_comment, .edit_message')) {



            return;



        }







        var selector = trigger.getAttribute('data-target') || trigger.getAttribute('data-bs-target');



        if (selector && selector.indexOf('#') === 0) {



            var modalId = selector.substring(1);



            var modal = document.getElementById(modalId);



            if (modal && modal.classList.contains('mtr-modal')) {



                e.preventDefault();



                e.stopPropagation();



                



                if (modalId === 'rapor') {



                    var dataId = trigger.getAttribute('data-id') || '';



                    var dataType = trigger.getAttribute('data-type') || '';



                    var rMsgId = modal.querySelector('#report_message_id');



                    var rIsCom = modal.querySelector('#report_is_comment');



                    var rReason = modal.querySelector('#report_reason');



                    var rDetail = modal.querySelector('#report_detail');



                    if (rMsgId) rMsgId.value = dataId;



                    if (rIsCom) rIsCom.value = (dataType === 'comment' ? '1' : '0');



                    if (rReason) rReason.value = '';



                    if (rDetail) rDetail.value = '';



                    var submitBtn = document.getElementById('submitReportBtn');



                    if (submitBtn) submitBtn.disabled = true;



                }



                



                openModalById(modalId);



            }



        }



    }



});







document.addEventListener('click', function(e) {



    var saveCommentButton = e.target.closest('#saveCommentChanges');



    var saveMessageButton = e.target.closest('#saveMessageChanges');



    if (!saveCommentButton && !saveMessageButton) {



        return;



    }







    e.preventDefault();



    e.stopImmediatePropagation();







    if (saveCommentButton) {



        if (typeof window.syncEditorContent === 'function') {



            window.syncEditorContent($('#editCommentTextarea'));



        }



        if (!hasValue('editCommentId')) {



            alert('Yorum bilgisi yÃ¼klenmeden kaydedilemez. LÃ¼tfen dÃ¼zenle butonuna tekrar basÄ±n.');



            return;



        }







        submitEditUpdate({



            button: saveCommentButton,



            url: 'update_comment.php',



            data: {



                com_id: document.getElementById('editCommentId').value,



                comment: document.getElementById('editCommentTextarea').value,



                gif_url: document.getElementById('editCommentGifUrl').value



            },



            contentId: 'comment_content_' + document.getElementById('editCommentId').value,



            htmlKey: 'new_comment',



            gifKey: 'new_gif_url',



            modalId: 'editCommentModal',



            errorPrefix: 'Yorum gÃ¼ncelleme'



        });



        return;



    }







    if (!hasValue('editMessageId')) {



        alert('Mesaj bilgisi yÃ¼klenmeden kaydedilemez. LÃ¼tfen dÃ¼zenle butonuna tekrar basÄ±n.');



        return;



    }







    if (typeof window.syncEditorContent === 'function') {



        window.syncEditorContent($('#editMessageTextarea'));



    }



    submitEditUpdate({



        button: saveMessageButton,



        url: 'update_message.php',



        data: {



            msg_id: document.getElementById('editMessageId').value,



            message: document.getElementById('editMessageTextarea').value,



            gif_url: document.getElementById('editMessageGifUrl').value



        },



        contentId: 'message_content_' + document.getElementById('editMessageId').value,



        htmlKey: 'new_message',



        gifKey: 'new_gif_url',



        modalId: 'editMessageModal',



        errorPrefix: 'Mesaj gÃ¼ncelleme'



    });







    function hasValue(elementId) {



        var input = document.getElementById(elementId);



        return !!(input && input.value);



    }



}, true);







function submitEditUpdate(options) {



    if (!window.jQuery) {



        alert('GÃ¼ncelleme iÃ§in gerekli JavaScript kÃ¼tÃ¼phanesi yÃ¼klenemedi.');



        return;



    }







    options.button.disabled = true;







    $.ajax({



        type: 'POST',



        url: options.url,



        data: options.data,



        dataType: 'json'



    }).done(function(response) {



        if (!response || !response.success) {



            alert(options.errorPrefix + ' hatasÄ±: ' + (response && response.error ? response.error : 'Bilinmeyen hata'));



            return;



        }







        updateEditedContent(options.contentId, response[options.htmlKey] || '', response[options.gifKey] || '');



        closeModalById(options.modalId);



    }).fail(function(jqXHR, textStatus, errorThrown) {



        console.error('AJAX error:', textStatus, errorThrown, jqXHR.responseText);



        alert('Sunucuyla iletiÅim hatasÄ±: ' + textStatus + ' ' + errorThrown);



    }).always(function() {



        options.button.disabled = false;



    });



}



window.submitEditUpdate = submitEditUpdate;







function updateEditedContent(contentId, html, gifUrl) {



    var content = document.getElementById(contentId);



    if (!content) {



        return;



    }







    content.innerHTML = html;







    var body = content.closest('.msg-body');



    if (!body) {



        return;



    }







    Array.prototype.slice.call(body.children).forEach(function(child) {



        if (child.classList && child.classList.contains('gif-eski')) {



            child.remove();



        }



    });







    if (!gifUrl || (html && html.indexOf(gifUrl) !== -1)) {



        return;



    }







    var gifWrapper = document.createElement('div');



    var gifContainer = document.createElement('div');



    var gifImage = document.createElement('img');



    gifWrapper.className = 'gif-eski';



    gifContainer.className = 'gif-container';



    gifImage.src = gifUrl;



    gifImage.alt = 'GIF';



    gifContainer.appendChild(gifImage);



    gifWrapper.appendChild(gifContainer);







    if (content.parentElement && content.parentElement.parentNode === body) {



        body.insertBefore(gifWrapper, content.parentElement.nextSibling);



    } else {



        body.appendChild(gifWrapper);



    }



}



window.updateEditedContent = updateEditedContent;