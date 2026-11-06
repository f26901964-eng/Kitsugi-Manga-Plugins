# SadScans v1.6.5 audit and candidate redesign

## Evidence, not assumptions

- https://github.com/keiyoushi/extensions-source/issues/13398 and comment https://github.com/keiyoushi/extensions-source/issues/13398#issuecomment-5679374586 : a third-party investigation reports a rebuilt Next.js frontend and protected requests via obfuscated Worker/WASM. This is an external report, not independent proof that every request today is signed. The current task's live test will record Worker/API observations if access is possible.
- https://github.com/keiyoushi/extensions-source/pull/13393 : upstream removal/restoration context. Simply copying an older extension does not establish compatibility with today's site.
- https://github.com/MALSync/MALSync/blob/master/src/pages-chibi/implementations/Sadscans/main.ts : browser tracking integration, NOT a working HTTP extension. Its `/series` selectors differ from the current site's `/seriler`; blindly copying them is inappropriate.
- Current `/seriler` frontend JS: https://sadscans.net/_next/static/chunks/1vy0fm3wxax8_.js : React search control placeholder `Aramak istediğiniz seriyi yazın`, 350ms debounce, dropdown controls, cards with `href`/`h3`, infinite-scroll sentinel `aria-hidden=true`, explicit empty/error UI. `search` passes through helper module 975157; prior extension sent raw input without investigating this preparation.
- Current series frontend JS: https://sadscans.net/_next/static/chunks/22yc01h34_ye1.js : canonical chapter `href`, `no`, `name`, paginated reader anchors containing `h3`; pagination buttons `aria-label="Sayfa N"`. Chapter href must be preserved, not reconstructed from the final path segment.
- Existing direct and Chromium results in `reports/sadscans-*.json` establish hard blocking on GitHub runner IPs. They do not establish the same response on the user's phone.

## Bugs confirmed in v1.6.5 source

1. Manual same-origin `fetch` from WebView was assumed equivalent to the site's own transport. No successful protected live response established that assumption. Opening a page first is not the same as letting its real frontend perform the operation.
2. Reader tried nine undocumented procedures with four input shapes each (36 requests). Every failed call could incur another 10s API timeout and 35s WebView timeout. Reader could exceed host deadlines before reaching a real reader page.
3. Reader regex scanned all scripts for asset URLs, including JSON-LD cover/schema images. One thumbnail was accepted as a successful whole chapter.
4. Chapter href processing took the last segment (chapter ID) as a slug, then appended that ID again. Canonical site-provided reader URLs were not preserved.
5. CommonHeadersInterceptor overwrote every Referer with the homepage, including chapter-specific image requests. It also overwrote caller Accept headers.
6. Old browser VM test covered a mocked unsigned fetch; a green result proved none of the site's actual request preparation.

## Candidate changes (not a confirmed live fix)

- Replace manual WebView API replay with actual site React controls for list/search and rendered detail-page pagination. The site's scripts/Worker retain responsibility for their own requests. No reverse-engineered signature or fabricated API candidate.
- DOM extraction follows selectors and pagination controls observed in the current page chunks. Empty state is accepted only when the site's explicit no-results UI appears, not while loading/challenged.
- Remove all 36 reader endpoint guesses and script regex fallback. Read actual reader `img` elements scoped to the selected chapter ID; schema-only and other-chapter URLs are excluded.
- Preserve canonical reader href and chapter image Referer.
- Tests execute the exact Kotlin-embedded JS in a DOM fixture, including native input events, sorting clicks, titles, detail pagination, reader scope and hard-block errors. These are contract tests, NOT live Android tests.
- Live probe now exercises actual frontend controls; it no longer reports a mocked/manual fetch as a frontend success. It attempts list, search, detail, reader and first image only when preceding stages genuinely pass.

## Remaining acceptance gates

- Real Android list/search success and complete chapter/page counts are not established.
- GitHub runner hard block may prevent remote live verification. No IP rotation, proxy or cookie copying is claimed to fix it.
- Worker/WASM report is not independently validated until the real page runs; don't present it as a conclusively identified signing algorithm.
- DOM reader stability is a heuristic, not proof all lazy-loaded pages were discovered; no full-reader success claim without comparing page totals.
- Browser-loaded image URLs can still fail in a different image HTTP client despite correct Referer. Image decoding/session continuity remains an integration gate.
- Opening a fresh WebView for every chapter pagination request may be too slow for very large series; performance is not yet measured on the host.
- Kotatsu's separate IkenParser still has its own legacy transport/reader code; this candidate targets the Keiyoushi APK used for the app update and does not assert Kotatsu compatibility.

Do not merge/publish this candidate solely because compilation succeeds. Update this record with actual build/live outcomes.

## Results from this session

- Candidate compiles successfully: run `42254821226`, build/catalog stages passed; 59 Python tests OK (12 unrelated optional-dependency skips). Artifact-save stage failed due to concurrent binary add/add rebase conflict, not compilation. A subsequent build is being run from the latest branch head, avoiding stale queued checkout.
- Exact DOM-script tests pass locally, including explicit no-results vs loading, schema-only reader rejection, canonical chapter href, pagination stale-row rejection and React hydration readiness.
- Current frontend live run `42254821232` fails at the first catalogue page with explicit hard block, zero Worker/API observations. The signer claim remains externally reported, not independently confirmed on this connection.
- The candidate is v1.6.6 on the working branch only. Main/app publication is still v1.6.5; no new release has been claimed as a working fix.
