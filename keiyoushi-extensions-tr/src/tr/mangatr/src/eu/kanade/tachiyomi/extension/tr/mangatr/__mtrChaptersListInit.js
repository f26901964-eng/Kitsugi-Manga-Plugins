(function($) {
  'use strict';
  if (window.__mtrChaptersListInit) { return; }
  window.__mtrChaptersListInit = true;

  let offset = 20;
  const limit = 100;
  let isLoading = false;
  let endOfData = false;
  let autoLoadEnabled = false;
  let currentAjax = null;

  function showResultsLoading() {
    $('#loading-div2').show();
    $('#results').addClass('is-loading');
  }

  function hideResultsLoading() {
    $('#loading-div2').hide();
    $('#results').removeClass('is-loading');
  }

  async function getS(mangaSlug) {
    let seed = 0;
    try {
      const cs = getComputedStyle(document.documentElement);
      const w = parseInt(cs.getPropertyValue('--mdx-white').trim(), 10) || 0;
      const b = parseInt(cs.getPropertyValue('--mdx-black').trim(), 10) || 0;
      seed = w + b;
    } catch (e) {}

    const timestamp = Math.floor(Date.now() / 1000);
    const raw = mangaSlug + '_' + seed + '_' + timestamp;
    let sig = '';
    if (window.crypto && window.crypto.subtle) {
      try {
        const buf = await window.crypto.subtle.digest('SHA-256', new TextEncoder().encode(raw));
        sig = Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, '0')).join('');
      } catch (e) {}
    }
    if (!sig) {
      let h = 0x811c9dc5;
      for (let i = 0; i < raw.length; i++) {
        h ^= raw.charCodeAt(i);
        h = Math.imul(h, 0x01000193);
      }
      sig = (h >>> 0).toString(16);
    }
    return { _t: timestamp, _sig: sig };
  }

  async function loadInitialResults(retryCount) {
    retryCount = retryCount || 0;
    const $results = $('#results');
    if (!$results.length) return;
    const mangaSlug = $results.data('manga') || '';
    const mangaTur = $results.data('tur') || '';

    showResultsLoading();
    const sec = await getS(mangaSlug);
    $.ajax({
      url: 'cek/fetch_pages_manga.php',
      method: 'POST',
      dataType: 'html',
      cache: false,
      data: {
        manga: mangaSlug,
        tur: mangaTur,
        offset: 0,
        _t: sec._t,
        _sig: sec._sig
      }
    })
    .done(function(html) {
      $results.html(html);
      if (window.__updateLoadMoreVisibility) window.__updateLoadMoreVisibility();
      hideResultsLoading();
    })
    .fail(function(xhr, textStatus) {
      if (xhr.status === 403 && retryCount < 3) {
        setTimeout(function() {
          loadInitialResults(retryCount + 1);
        }, 300);
        return;
      }
      console.error('İlk bölüm listesi yüklenemedi.', textStatus, xhr);
      $results.html('<div class="notice-card">Bölüm listesi yüklenemedi. Lütfen sayfayı yenileyin.</div>');
      hideResultsLoading();
    });
  }

  async function searchChapter(chapterNumber) {
    const mangaSlug = $('#results').data('manga') || '';
    const sec = await getS(mangaSlug);
    $.ajax({
      url: 'cek/bolum_cek.php',
      method: 'POST',
      dataType: 'html',
      data: {
        manga: mangaSlug,
        chapter: chapterNumber,
        _t: sec._t,
        _sig: sec._sig
      },
      beforeSend: function() { $('#bolumara').html('<div class="notice-card">Aranıyor...</div>').show(); }
    })
    .done(function(response) { $('#bolumara').html(response).show(); })
    .fail(function(xhr, textStatus, errorThrown) {
      if (xhr.status === 403) {
        $('#bolumara').html('<div class="notice-card">Oturum süresi doldu. Lütfen sayfayı yenileyin.</div>').show();
      } else {
        console.error('AJAX Error:', textStatus, errorThrown, xhr);
        $('#bolumara').html('<div class="notice-card">Arama sırasında bir hata oluştu: ' + textStatus + '</div>').show();
      }
    });
  }

  window.__updateLoadMoreVisibility = function() {
    const hasMore = $('#has-more-chapters').length ? $('#has-more-chapters').val() !== '0' : true;
    endOfData = !hasMore;
    if (hasMore) {
      $('#btn-load-more').show().text('Devamını Göster');
    } else {
      $('#btn-load-more').hide();
    }
  };

  async function loadMoreChapters() {
    if (isLoading || endOfData) return;
    const $results = $('#results');
    const mangaSlug = $results.data('manga') || '';
    const mangaTur = $results.data('tur') || '';

    isLoading = true;
    $('#loading-div').show();
    $('#btn-load-more').hide();
    const sec = await getS(mangaSlug);
    currentAjax = $.ajax({
      url: 'cek/fetch_pages_manga.php',
      method: 'POST',
      data: {
        manga: mangaSlug,
        tur: mangaTur,
        offset: offset,
        _t: sec._t,
        _sig: sec._sig
      },
      success: function(html) {
        if ($.trim(html) === '') {
          endOfData = true;
          isLoading = false;
          $('#loading-div').hide();
        } else {
          if ($('#results .bento-episodes-grid').length) {
            $('#results .bento-episodes-grid').append(html);
          } else {
            $('#results').append(html);
          }
          offset += limit;
          isLoading = false;
          $('#loading-div').hide();
          if (window.__updateLoadMoreVisibility) {
            window.__updateLoadMoreVisibility();
          } else {
            $('#btn-load-more').show();
          }
        }
      },
      error: function() {
        isLoading = false;
        $('#loading-div').hide();
        $('#btn-load-more').text('Hata oluştu, tekrar dene').show();
      }
    });
  }

  window.__resetLoadMoreOffset = function(page) {
    if (currentAjax) {
      currentAjax.abort();
      currentAjax = null;
    }
    offset = page * 20;
    autoLoadEnabled = false;
    endOfData = false;
    isLoading = false;
    if (window.__updateLoadMoreVisibility) {
      window.__updateLoadMoreVisibility();
    } else {
      $('#btn-load-more').text('Devamını Göster').show();
    }
  };

  $(function() {
    loadInitialResults();
    $(document).off('click.mtrChapterSearch', '#searchButton');
    $(document).on('click.mtrChapterSearch', '#searchButton', function() {
      const chapterNumber = $.trim($('#chapterSearch').val());
      if (chapterNumber !== '') {
        searchChapter(chapterNumber);
      } else {
        $('#bolumara').html('<div class="notice-card">Lütfen bir bölüm numarası girin.</div>').show();
      }
    });
    $(document).off('keydown.mtrChapterSearch', '#chapterSearch');
    $(document).on('keydown.mtrChapterSearch', '#chapterSearch', function(event) {
      if (event.key === 'Enter') {
        event.preventDefault();
        $('#searchButton').trigger('click');
      }
    });
    $('#btn-load-more').on('click', function() {
      autoLoadEnabled = true;
      loadMoreChapters();
    });
    $(window).scroll(function() {
      if (autoLoadEnabled && !isLoading && !endOfData) {
        if ($(window).scrollTop() + $(window).height() > $(document).height() - 400) {
          loadMoreChapters();
        }
      }
    });
  });
})(jQuery);