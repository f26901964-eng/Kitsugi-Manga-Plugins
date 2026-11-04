/* Originals are requested only when a reader opens a cover lightbox. */
(function () {
  'use strict';
  document.addEventListener('click', function (event) {
    var target = event.target;
    if (!(target instanceof Element)) return;
    var cover = target.closest('.js-detail-cover[data-original]');
    if (cover) {
      var lightbox = document.getElementById('detailLightbox');
      var image = document.getElementById('detailLightboxImage');
      if (lightbox && image) {
        event.preventDefault();
        event.stopImmediatePropagation();
        image.src = cover.getAttribute('data-original') || cover.src;
        lightbox.classList.add('is-open');
      }
    }
    if (target.closest('.poster-card__image-link-manga')) {
      var original = document.querySelector('#cover-lightbox img[data-src]');
      if (original && !original.getAttribute('src')) original.src = original.getAttribute('data-src');
    }
  }, true);
})();
