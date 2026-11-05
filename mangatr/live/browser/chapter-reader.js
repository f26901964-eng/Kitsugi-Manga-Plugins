(function() {
    'use strict';
    
    var modal = document.getElementById('fansubGroupSelector');
    var skipBtn = document.getElementById('skipGroupBtn');
    
    if (!modal) return;
    
    var mangaSlug = modal.getAttribute('data-manga-slug') || '';
    var currentChapterId = modal.getAttribute('data-chapter-id') || modal.getAttribute('data-current-chapter-id') || '';
    
    function showModal() {
        if (modal) {
            modal.classList.remove('modal-hidden');
            document.body.style.overflow = 'hidden';
        }
    }
    
    function hideModal() {
        if (modal) {
            modal.classList.add('modal-hidden');
            document.body.style.overflow = '';
        }
    }
    
    function shouldShowModal() {
        var urlParams = new URLSearchParams(window.location.search);
        if (urlParams.get('fs_done') === '1') {
            try {
                var url = new URL(window.location.href);
                url.searchParams.delete('fs_done');
                var cleanUrl = url.pathname + (url.search ? url.search : '');
                window.history.replaceState({}, document.title, cleanUrl);
            } catch (e) {
                window.history.replaceState({}, document.title, window.location.pathname);
            }
            return false;
        }
        
        try {
            var key = 'fansub_selected_' + mangaSlug + '_' + currentChapterId;
            if (sessionStorage.getItem(key) === '1') {
                return false;
            }
        } catch (e) {}
        return true;
    }
    
    function markModalShown() {
        try {
            var key = 'fansub_selected_' + mangaSlug + '_' + currentChapterId;
            sessionStorage.setItem(key, '1');
        } catch (e) {}
    }
    
    if (skipBtn) {
        skipBtn.addEventListener('click', function() {
            markModalShown();
            hideModal();
        });
    }
    
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape' && modal && !modal.classList.contains('modal-hidden')) {
            markModalShown();
            hideModal();
        }
    });
    
    if (modal) {
        modal.addEventListener('click', function(e) {
            if (e.target === modal) {
                markModalShown();
                hideModal();
            }
        });
    }
    
    var groupLinks = document.querySelectorAll('.fansub-group-item');
    groupLinks.forEach(function(link) {
        link.addEventListener('click', function() {
            markModalShown();
            hideModal();
        });
    });
    
    function init() {
        if (shouldShowModal()) {
            setTimeout(showModal, 100);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
    
    window.skipGroupSelector = function() {
        markModalShown();
        hideModal();
    };
    window.hideGroupSelector = hideModal;
    window.showGroupSelector = showModal;
    window.openGroupSelector = showModal;
})();
