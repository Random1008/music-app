'use strict';
/* Hermes Music — client web connecté à Jellyfin (prototype V0.3).
   Toutes les données viennent de /jf/* (proxy local -> Jellyfin). La clé API reste côté serveur. */

/* ------------------------------------------------------------------ utils */
const $ = (s) => document.querySelector(s);
const ico = (c) => h('i', { class: 'ph ph-' + c });
const h = (tag, a = {}, ...kids) => {
  const n = document.createElement(tag);
  for (const k in a) {
    const v = a[k];
    if (v == null || v === false) continue;
    if (k === 'class') n.className = v;
    else if (k === 'html') n.innerHTML = v;
    else if (k === 'text') n.textContent = v;
    else if (k.startsWith('on')) n.addEventListener(k.slice(2).toLowerCase(), v);
    else n.setAttribute(k, v);
  }
  for (const kid of kids) if (kid != null && kid !== false) n.append(kid.nodeType ? kid : document.createTextNode(kid));
  return n;
};
const ticks = (t) => (t || 0) / 1e7;
const fmt = (s) => {
  s = Math.max(0, Math.floor(s || 0));
  return Math.floor(s / 60) + ':' + String(Math.floor(s % 60)).padStart(2, '0');
};
const plural = (n, s) => n + ' ' + s + (n > 1 ? 's' : '');
const artistOf = (t) => (t.Artists && t.Artists.length ? t.Artists.join(', ') : t.AlbumArtist || 'Artiste inconnu');
const artId = (it) => (it.Type === 'Audio' || it.MediaType === 'Audio' ? it.AlbumId || it.Id : it.Id);

let toastTimer;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 2600);
}

/* ------------------------------------------------------------- état global */
const S = {
  ready: false, uid: null, userName: 'R',
  tab: 'home', libTab: 'albums', query: '',
  albums: [], artists: [], recent: [], played: [],
  lib: {}, favs: [], playlists: [], results: null,
  view: null,                       // {type:'album'|'artist', loading, data, id}
  queue: [], qi: 0, playing: false, pos: 0, dur: 0,
  shuffle: false, repeat: 'off',    // 'off' | 'all' | 'one'
  playerOpen: false, queueOpen: false, session: 'hm-' + Math.random().toString(36).slice(2),
};

const F = 'ImageTags,AlbumPrimaryImageTag,AlbumArtist,AlbumArtists,Artists,Album,AlbumId,ProductionYear,ChildCount,RunTimeTicks,IndexNumber,DateCreated,UserData';

async function api(path, opts) {
  const r = await fetch('/jf' + path, opts);
  if (!r.ok) throw new Error(path + ' -> ' + r.status);
  return r.status === 204 ? null : r.json();
}
const coverUrl = (id, px) => `/jf/Items/${id}/Images/Primary?maxHeight=${px}&quality=90`;

/* Une pochette n'existe pas toujours dans Jellyfin (artistes sans photo, etc.) :
   on le vérifie AVANT de requêter, pour éviter des 404 inutiles. */
function hasArt(it) {
  return !!(it.AlbumPrimaryImageTag || (it.ImageTags && it.ImageTags.Primary));
}
function cover(item, px) {
  const id = artId(item);
  const box = h('div', { class: 'cover' });
  if (id && hasArt(item)) {
    const im = h('img', { src: coverUrl(id, px || 300), alt: '', loading: 'lazy' });
    im.addEventListener('error', () => { im.remove(); box.classList.add('blank'); });
    box.append(im);
  } else box.classList.add('blank');
  return box;
}

/* ------------------------------------------------------------ audio réel */
const audio = new Audio();
audio.preload = 'metadata';
let seeking = false;
audio.addEventListener('loadedmetadata', () => {
  if (isFinite(audio.duration) && audio.duration > 0) S.dur = audio.duration;
  paintProgress();
});
audio.addEventListener('timeupdate', () => { if (!seeking) { S.pos = audio.currentTime; paintProgress(); } });
audio.addEventListener('play', () => { S.playing = true; paintPlay(); });
audio.addEventListener('pause', () => { S.playing = false; paintPlay(); });
audio.addEventListener('ended', () => next(S.repeat !== 'one'));
audio.addEventListener('error', () => { if (audio.src) toast('Lecture impossible'); });

function cur() { return S.queue[S.qi] || null; }

function playList(list, i) {
  S.queue = list.slice(); S.qi = i;
  loadCur(true);
  S.playerOpen = true;
  render();
}
function loadCur(autoplay) {
  const t = cur(); if (!t) return;
  S.pos = 0; S.dur = ticks(t.RunTimeTicks);
  audio.src = `/jf/Audio/${t.Id}/stream?static=true&UserId=${S.uid}`;
  if (autoplay) audio.play().catch(() => toast('Lecture impossible'));
  report('Playing');
}
function next(auto) {
  if (!S.queue.length) return;
  const seq = S.shuffle ? shuffleNext() : (S.qi + 1) % S.queue.length;
  if (!S.shuffle && seq === 0 && S.repeat === 'off' && auto) { audio.pause(); return; }
  S.qi = seq; loadCur(true); render();
}
function prev() {
  if (!S.queue.length) return;
  if (audio.currentTime > 4) { audio.currentTime = 0; return; }
  S.qi = (S.qi - 1 + S.queue.length) % S.queue.length; loadCur(true); render();
}
function shuffleNext() {
  if (S.queue.length < 2) return S.qi;
  let n; do { n = Math.floor(Math.random() * S.queue.length); } while (n === S.qi);
  return n;
}
function togglePlay() {
  if (!cur()) return;
  if (audio.paused) { audio.play().catch(() => toast('Lecture impossible')); report('Playing'); }
  else { audio.pause(); report('Stopped'); }
}
function cycleRepeat() {
  S.repeat = S.repeat === 'off' ? 'all' : S.repeat === 'all' ? 'one' : 'off';
  toast(S.repeat === 'off' ? 'Répétition désactivée' : S.repeat === 'all' ? 'Répéter la file' : 'Répéter le morceau');
  render();
}
function report(kind) {
  const t = cur(); if (!t || !S.uid) return;
  fetch('/jf/Sessions/Playing' + (kind === 'Stopped' ? '/Stopped' : ''), {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ItemId: t.Id, MediaSourceId: t.Id, PlaySessionId: S.session, CanSeek: true,
      IsPaused: kind === 'Stopped', IsMuted: false, PlayMethod: 'DirectStream',
      PositionTicks: Math.round((audio.currentTime || 0) * 1e7),
    }),
  }).catch(() => {});
}
setInterval(() => { if (!audio.paused && audio.currentTime > 0) report('Playing'); }, 15000);

async function favToggle(t, e) {
  if (e) e.stopPropagation();
  const on = !!(t.UserData && t.UserData.IsFavorite);
  try {
    await fetch(`/jf/Users/${S.uid}/FavoriteItems/${t.Id}`, { method: on ? 'DELETE' : 'POST' });
    t.UserData = Object.assign({}, t.UserData, { IsFavorite: !on });
    toast(on ? 'Retiré des favoris' : 'Ajouté aux favoris');
    render();
  } catch (err) { toast('Favoris indisponibles'); }
}

/* ----------------------------------------------------------- chargements */
async function boot() {
  render();
  try {
    const users = await api('/Users');
    S.uid = users[0].Id; S.userName = users[0].Name || 'R';
  } catch (e) {
    $('#view').innerHTML = '';
    $('#view').append(h('div', { class: 'empty' },
      ico('warning-circle'), h('b', { text: 'Jellyfin injoignable' }),
      h('p', { text: 'Le serveur de musique ne répond pas. Vérifie que le conteneur Jellyfin tourne, puis recharge.' })));
    return;
  }
  await refreshHome();
  S.ready = true; render();
}

async function refreshHome() {
  const q = (s) => '/Items?' + s;
  const [albums, artists, recent, played, pls] = await Promise.all([
    api(q(`IncludeItemTypes=MusicAlbum&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=18&Fields=${F}&UserId=${S.uid}`)).catch(() => ({ Items: [] })),
    api(`/Artists?Limit=14&Recursive=true&SortBy=SortName&Fields=ImageTags`).catch(() => ({ Items: [] })),
    api(q(`IncludeItemTypes=Audio&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=12&Fields=${F}&UserId=${S.uid}`)).catch(() => ({ Items: [] })),
    api(`/Users/${S.uid}/Items?Filters=IsPlayed&Recursive=true&IncludeItemTypes=Audio&SortBy=DatePlayed&SortOrder=Descending&Limit=8&Fields=${F}`).catch(() => ({ Items: [] })),
    api(q(`IncludeItemTypes=Playlist&Recursive=true&Fields=${F}`)).catch(() => ({ Items: [] })),
  ]);
  S.albums = albums.Items || [];
  S.artists = artists.Items || [];
  S.recent = recent.Items || [];
  S.played = played.Items || [];
  S.playlists = pls.Items || [];
}

async function loadLib(tab) {
  if (S.lib[tab]) return;
  const F_ = F;
  let r;
  if (tab === 'albums') r = await api(`/Items?IncludeItemTypes=MusicAlbum&Recursive=true&SortBy=SortName&Limit=120&Fields=${F_}&UserId=${S.uid}`);
  else if (tab === 'artists') r = await api(`/Artists?Limit=120&Recursive=true&SortBy=SortName&Fields=ImageTags`);
  else if (tab === 'tracks') r = await api(`/Items?IncludeItemTypes=Audio&Recursive=true&SortBy=SortName&Limit=150&Fields=${F_}&UserId=${S.uid}`);
  else if (tab === 'favs') r = await api(`/Users/${S.uid}/Items?Filters=IsFavorite&Recursive=true&IncludeItemTypes=Audio&SortBy=SortName&Limit=150&Fields=${F_}`);
  else r = { Items: [] };
  S.lib[tab] = r.Items || [];
}

function openAlbum(id) {
  S.view = { type: 'album', id, loading: true };
  render();
  api(`/Items/${id}?Fields=${F}&UserId=${S.uid}`).then((a) =>
    api(`/Items?ParentId=${id}&IncludeItemTypes=Audio&Recursive=true&SortBy=ParentIndexNumber,IndexNumber&Fields=${F}&UserId=${S.uid}`)
      .then((tr) => { S.view = { type: 'album', id, data: a, tracks: tr.Items || [] }; render(); })
  ).catch(() => { S.view = { type: 'album', id, error: true }; render(); });
}
function openArtist(id) {
  S.view = { type: 'artist', id, loading: true };
  render();
  Promise.all([
    api(`/Items/${id}?Fields=${F}`),
    api(`/Items?ArtistIds=${id}&IncludeItemTypes=MusicAlbum&Recursive=true&SortBy=ProductionYear,SortName&Limit=60&Fields=${F}&UserId=${S.uid}`),
    api(`/Items?ArtistIds=${id}&IncludeItemTypes=Audio&Recursive=true&SortBy=PlayCount,SortName&SortOrder=Descending&Limit=30&Fields=${F}&UserId=${S.uid}`),
  ]).then(([a, al, tr]) => { S.view = { type: 'artist', id, data: a, albums: al.Items || [], tracks: tr.Items || [] }; render(); })
    .catch(() => { S.view = { type: 'artist', id, error: true }; render(); });
}

let searchTimer;
function onQuery(v) {
  S.query = v;
  clearTimeout(searchTimer);
  if (!v.trim()) { S.results = null; render(); return; }
  searchTimer = setTimeout(async () => {
    try {
      const r = await api(`/Items?searchTerm=${encodeURIComponent(v)}&IncludeItemTypes=Audio,MusicAlbum,MusicArtist&Recursive=true&Limit=30&Fields=${F}&UserId=${S.uid}`);
      S.results = r.Items || [];
    } catch (e) { S.results = []; }
    render();
  }, 260);
}

/* -------------------------------------------------------------- rendu UI */
function render() {
  renderTopbar(); renderTabbar();
  const v = $('#view');
  v.innerHTML = '';
  if (S.view) {
    if (S.view.type === 'album') renderAlbum(v);
    else renderArtist(v);
  } else if (S.tab === 'home') renderHome(v);
  else if (S.tab === 'search') renderSearch(v);
  else if (S.tab === 'lib') renderLib(v);
  else renderPlaylists(v);
  renderMini(); renderPlayer();
}

function renderTopbar() {
  const T = {
    home: ['Bonsoir', 'Ta bibliothèque'], search: ['Recherche', 'Trouver un son'],
    lib: ['Bibliothèque', 'Tout ton catalogue'], pl: ['Playlists', 'Tes sélections'],
  }[S.tab] || ['Bonsoir', 'Ta bibliothèque'];
  const t = $('#topbar'); t.innerHTML = '';
  t.append(
    h('div', {}, h('div', { class: 'tb-who', text: T[0] }), h('div', { class: 'tb-title', text: T[1] })),
    h('div', { class: 'tb-right' },
      h('button', { class: 'tb-btn', title: 'Réglages', onclick: () => toast('Réglages — à venir') }, ico('gear-six')),
      h('div', { class: 'tb-av', text: String(S.userName || 'R')[0].toUpperCase() })));
}

function renderTabbar() {
  const tabs = [['home', 'house', 'Accueil'], ['search', 'magnifying-glass', 'Recherche'],
                ['lib', 'vinyl-record', 'Bibliothèque'], ['pl', 'list-dashes', 'Playlists']];
  const b = $('#tabbar'); b.innerHTML = '';
  for (const [id, icon, label] of tabs)
    b.append(h('button', { class: 'tab' + (S.tab === id && !S.view ? ' on' : ''),
      onclick: () => { S.tab = id; S.view = null; if (id === 'lib') loadLib(S.libTab).then(render); render(); } },
      ico(icon), h('span', { text: label })));
}

const secHead = (title, action, onAction) => h('div', { class: 'sec-h' },
  h('div', { class: 'sec-t', text: title }),
  action ? h('button', { class: 'sec-a', onclick: onAction, text: action }) : null);

function albumCard(a) {
  return h('button', { class: 'card', onclick: () => openAlbum(a.Id) },
    cover(a, 300), h('div', { class: 'card-t', text: a.Name }),
    h('div', { class: 'card-s', text: a.AlbumArtist || (a.Artists || []).join(', ') || '' }));
}
function artistCircle(a) {
  return h('button', { class: 'art', onclick: () => openArtist(a.Id) },
    cover(a, 200), h('div', { class: 'art-n', text: a.Name }));
}
function trackRow(t, i, list) {
  const on = cur() && cur().Id === t.Id;
  return h('div', { class: 'row' + (on ? ' on' : ''), onclick: () => playList(list, i) },
    h('div', { class: 'row-no', text: String(i + 1).padStart(2, '0') }),
    cover(t, 100),
    h('div', { class: 'row-b' },
      h('div', { class: 'row-t', text: t.Name }),
      h('div', { class: 'row-s', text: artistOf(t) + (t.Album ? ' · ' + t.Album : '') })),
    h('div', { class: 'row-d', text: fmt(ticks(t.RunTimeTicks)) }),
    h('button', { class: 'row-x', title: 'Favori', onclick: (e) => favToggle(t, e) },
      ico(t.UserData && t.UserData.IsFavorite ? 'heart' : 'heart')));
}

function fmtIconOf(t) { return t.UserData && t.UserData.IsFavorite ? 'ph-fill ph-heart' : 'ph ph-heart'; }
function trackRowFixed(t, i, list) {
  const row = trackRow(t, i, list);
  const x = row.querySelector('.row-x');
  x.innerHTML = '<i class="' + fmtIconOf(t) + '"></i>';
  if (t.UserData && t.UserData.IsFavorite) x.style.color = 'var(--acc)';
  return row;
}

function renderHome(v) {
  if (!S.ready) { v.append(h('div', { class: 'spin' }, ico('circle-notch'))); return; }

  /* — bloc « reprendre » : dernier morceau joué, sinon dernière nouveauté — */
  const heroTrack = S.played[0] || S.recent[0] || null;
  const heroAlbum = S.albums[0] || null;
  if (heroTrack) {
    const pct = heroTrack.UserData && heroTrack.UserData.PlayedPercentage ? Math.min(100, heroTrack.UserData.PlayedPercentage) : 0;
    v.append(h('div', { class: 'hero', onclick: () => { playList([heroTrack], 0); } },
      h('div', { class: 'hero-glow' }), cover(heroTrack, 200),
      h('div', { class: 'hero-b' },
        h('div', { class: 'hero-k', text: S.played[0] ? 'REPRENDRE L\'ÉCOUTE' : 'NOUVEAUTÉ' }),
        h('div', { class: 'hero-t', text: heroTrack.Name }),
        h('div', { class: 'hero-a', text: artistOf(heroTrack) }),
        pct > 0 ? h('div', { class: 'hero-p' }, h('i', { style: 'width:' + pct + '%' })) : null),
      h('div', { class: 'hero-play' }, ico(S.playing ? 'pause' : 'play'))));
  } else if (heroAlbum) {
    v.append(h('div', { class: 'hero', onclick: () => openAlbum(heroAlbum.Id) },
      h('div', { class: 'hero-glow' }), cover(heroAlbum, 200),
      h('div', { class: 'hero-b' },
        h('div', { class: 'hero-k', text: 'DERNIER AJOUT' }),
        h('div', { class: 'hero-t', text: heroAlbum.Name }),
        h('div', { class: 'hero-a', text: heroAlbum.AlbumArtist || '' })),
      h('div', { class: 'hero-play' }, ico('play'))));
  }

  /* — albums récents — */
  if (S.albums.length) {
    const s = h('div', { class: 'sec' }, secHead('Albums récents', 'TOUT VOIR',
      () => { S.tab = 'lib'; S.libTab = 'albums'; loadLib('albums').then(render); render(); }));
    const sc = h('div', { class: 'hscroll' });
    S.albums.slice(0, 12).forEach((a) => sc.append(albumCard(a)));
    s.append(sc); v.append(s);
  }

  /* — récemment écouté (si historique réel) — */
  if (S.played.length > 1) {
    const s = h('div', { class: 'sec' }, secHead('Récemment écouté'));
    const r = h('div', { class: 'rows' });
    S.played.slice(0, 5).forEach((t, i) => r.append(trackRowFixed(t, i, S.played)));
    s.append(r); v.append(s);
  }

  /* — artistes — */
  if (S.artists.length) {
    const s = h('div', { class: 'sec' }, secHead('Artistes', 'TOUT VOIR',
      () => { S.tab = 'lib'; S.libTab = 'artists'; loadLib('artists').then(render); render(); }));
    const sc = h('div', { class: 'hscroll' });
    S.artists.forEach((a) => sc.append(artistCircle(a)));
    s.append(sc); v.append(s);
  }

  /* — nouveautés — */
  if (S.recent.length) {
    const s = h('div', { class: 'sec' }, secHead('Récemment ajouté'));
    const r = h('div', { class: 'rows' });
    S.recent.slice(0, 6).forEach((t, i) => r.append(trackRowFixed(t, i, S.recent)));
    s.append(r); v.append(s);
  }

  if (S.playlists.length) {
    const s = h('div', { class: 'sec' }, secHead('Playlists'));
    const r = h('div', { class: 'rows' });
    S.playlists.slice(0, 4).forEach((p) => r.append(listRow(p, () => { S.tab = 'pl'; render(); })));
    s.append(r); v.append(s);
  }
}

function listRow(item, onclick) {
  return h('div', { class: 'row', onclick },
    h('div', { class: 'row-no' }), cover(item, 100),
    h('div', { class: 'row-b' },
      h('div', { class: 'row-t', text: item.Name }),
      h('div', { class: 'row-s', text: plural(item.ChildCount || 0, 'morceau') })),
    h('div', { class: 'row-x' }, ico('caret-right')));
}

function renderSearch(v) {
  const box = h('div', { class: 'search' }, ico('magnifying-glass'),
    h('input', { type: 'search', placeholder: 'Morceaux, albums, artistes…', value: S.query,
      oninput: (e) => onQuery(e.target.value) }));
  v.append(box);
  if (!S.query.trim()) {
    v.append(h('div', { class: 'empty' }, ico('magnifying-glass-glass'),
      h('b', { text: 'Cherche dans ta musique' }),
      h('p', { text: 'Titres, albums et artistes de ta bibliothèque Jellyfin.' })));
    return;
  }
  if (S.results === null) { v.append(h('div', { class: 'spin' }, ico('circle-notch'))); return; }
  if (!S.results.length) {
    v.append(h('div', { class: 'empty' }, ico('smiley-sad'), h('b', { text: 'Aucun résultat' }),
      h('p', { text: 'Rien ne correspond à « ' + S.query + ' ».' })));
    return;
  }
  const songs = S.results.filter((x) => x.Type === 'Audio');
  const albs = S.results.filter((x) => x.Type === 'MusicAlbum');
  const arts = S.results.filter((x) => x.Type === 'MusicArtist');
  if (songs.length) {
    const s = h('div', { class: 'sec' }, secHead(plural(songs.length, 'morceau')));
    const r = h('div', { class: 'rows' });
    songs.forEach((t, i) => r.append(trackRowFixed(t, i, songs)));
    s.append(r); v.append(s);
  }
  if (albs.length) {
    const s = h('div', { class: 'sec' }, secHead(plural(albs.length, 'album')));
    const sc = h('div', { class: 'hscroll' });
    albs.forEach((a) => sc.append(albumCard(a)));
    s.append(sc); v.append(s);
  }
  if (arts.length) {
    const s = h('div', { class: 'sec' }, secHead(plural(arts.length, 'artiste')));
    const sc = h('div', { class: 'hscroll' });
    arts.forEach((a) => sc.append(artistCircle(a)));
    s.append(sc); v.append(s);
  }
}

function renderLib(v) {
  const chips = [['albums', 'Albums'], ['artists', 'Artistes'], ['tracks', 'Morceaux'], ['favs', 'Favoris']];
  const c = h('div', { class: 'chips' });
  chips.forEach(([id, label]) => c.append(h('button', {
    class: 'chip' + (S.libTab === id ? ' on' : ''),
    onclick: () => { S.libTab = id; loadLib(id).then(render); render(); },
  }, label)));
  v.append(c);

  const items = S.lib[S.libTab];
  if (!items) { v.append(h('div', { class: 'spin' }, ico('circle-notch'))); return; }
  if (!items.length) {
    const msg = S.libTab === 'favs'
      ? ['Aucun favori', 'Ouvre un morceau et touche le cœur pour l\'ajouter ici. Synchronisé avec Jellyfin.']
      : ['Rien à afficher', 'Cette section est vide.'];
    v.append(h('div', { class: 'empty' }, ico('music-notes'), h('b', { text: msg[0] }), h('p', { text: msg[1] })));
    return;
  }
  if (S.libTab === 'albums') {
    const g = h('div', { class: 'hscroll', style: 'flex-wrap:wrap;gap:14px' });
    items.forEach((a) => g.append(albumCard(a)));
    v.append(g);
  } else if (S.libTab === 'artists') {
    const g = h('div', { class: 'hscroll', style: 'flex-wrap:wrap;gap:12px;justify-content:flex-start' });
    items.forEach((a) => g.append(artistCircle(a)));
    v.append(g);
  } else {
    const r = h('div', { class: 'rows' });
    items.forEach((t, i) => r.append(trackRowFixed(t, i, items)));
    v.append(r);
  }
}

function renderPlaylists(v) {
  if (!S.playlists.length) {
    v.append(h('div', { class: 'empty' }, ico('list-dashes'),
      h('b', { text: 'Aucune playlist' }),
      h('p', { text: 'Aucune playlist n\'existe encore dans Jellyfin. Crée-en une depuis l\'app ou l\'interface Jellyfin, elle apparaîtra ici automatiquement.' })));
    return;
  }
  const r = h('div', { class: 'rows' });
  S.playlists.forEach((p) => r.append(listRow(p, () => toast('Ouverture des playlists à venir'))));
  v.append(r);
}

function renderAlbum(v) {
  const V = S.view;
  v.append(h('div', { class: 'back', onclick: () => { S.view = null; render(); } },
    ico('caret-left'), h('span', { text: 'RETOUR' })));
  if (V.loading) { v.append(h('div', { class: 'spin' }, ico('circle-notch'))); return; }
  if (V.error) { v.append(h('div', { class: 'empty' }, ico('warning-circle'), h('b', { text: 'Album indisponible' }))); return; }
  const a = V.data, tr = V.tracks || [];
  const secs = tr.reduce((x, t) => x + ticks(t.RunTimeTicks), 0);
  v.append(h('div', { class: 'head' }, cover(a, 500),
    h('div', { class: 'head-t', text: a.Name }),
    h('div', { class: 'head-s', text: a.AlbumArtist || (a.Artists || []).join(', ') || '' }),
    h('div', { class: 'head-m', text: [a.ProductionYear, plural(tr.length, 'morceau'), fmt(secs)].filter(Boolean).join('  ·  ') })));
  v.append(h('div', { class: 'acts' },
    h('button', { class: 'btn', onclick: () => playList(tr, 0) }, ico('play'), 'Lecture'),
    h('button', { class: 'btn ghost', onclick: () => { const s = tr.slice().sort(() => Math.random() - .5); playList(s, 0); } }, ico('shuffle'), 'Aléatoire')));
  const r = h('div', { class: 'rows' });
  tr.forEach((t, i) => r.append(trackRowFixed(t, i, tr)));
  v.append(r);
}

function renderArtist(v) {
  const V = S.view;
  v.append(h('div', { class: 'back', onclick: () => { S.view = null; render(); } },
    ico('caret-left'), h('span', { text: 'RETOUR' })));
  if (V.loading) { v.append(h('div', { class: 'spin' }, ico('circle-notch'))); return; }
  if (V.error) { v.append(h('div', { class: 'empty' }, ico('warning-circle'), h('b', { text: 'Artiste indisponible' }))); return; }
  const a = V.data, al = V.albums || [], tr = V.tracks || [];
  v.append(h('div', { class: 'head round' }, cover(a, 400),
    h('div', { class: 'head-t', text: a.Name }),
    h('div', { class: 'head-m', text: [plural(al.length, 'album'), plural(tr.length, 'morceau')].join('  ·  ') })));
  if (tr.length) {
    v.append(h('div', { class: 'acts' },
      h('button', { class: 'btn', onclick: () => playList(tr, 0) }, ico('play'), 'Lecture'),
      h('button', { class: 'btn ghost', onclick: () => { const s = tr.slice().sort(() => Math.random() - .5); playList(s, 0); } }, ico('shuffle'), 'Aléatoire')));
    const s = h('div', { class: 'sec' }, secHead('Morceaux les plus écoutés'));
    const r = h('div', { class: 'rows' });
    tr.slice(0, 10).forEach((t, i) => r.append(trackRowFixed(t, i, tr)));
    s.append(r); v.append(s);
  }
  if (al.length) {
    const s = h('div', { class: 'sec' }, secHead(plural(al.length, 'album')));
    const sc = h('div', { class: 'hscroll' });
    al.forEach((x) => sc.append(albumCard(x)));
    s.append(sc); v.append(s);
  }
}

/* ---------------------------------------------------------- lecteurs */
function renderMini() {
  const m = $('#mini');
  const t = cur();
  if (!t || S.playerOpen) { m.hidden = true; m.innerHTML = ''; return; }
  m.hidden = false; m.innerHTML = '';
  m.append(
    h('div', { class: 'mini-in', onclick: () => { S.playerOpen = true; render(); } },
      cover(t, 100),
      h('div', { class: 'mini-b' },
        h('div', { class: 'mini-t', text: t.Name }),
        h('div', { class: 'mini-s', text: artistOf(t) })),
      h('button', { class: 'mini-btn', onclick: (e) => { e.stopPropagation(); togglePlay(); } },
        ico(S.playing ? 'pause' : 'play')),
      h('button', { class: 'mini-btn', onclick: (e) => { e.stopPropagation(); next(false); } }, ico('skip-forward'))),
    h('div', { class: 'mini-p' }, h('i', { id: 'miniP', style: 'width:0%' })));
  paintProgress();
}

function renderPlayer() {
  const p = $('#player');
  const t = cur();
  if (!S.playerOpen || !t) { p.hidden = true; p.innerHTML = ''; p.classList.remove('in'); return; }
  p.hidden = false; p.innerHTML = '';
  const pct = S.dur ? Math.min(100, (S.pos / S.dur) * 100) : 0;

  p.append(h('div', { class: 'pl-top' },
    h('button', { class: 'pl-b sm', title: 'Réduire', onclick: () => { S.playerOpen = false; render(); } }, ico('caret-down')),
    h('div', { class: 'pl-lab', text: 'LECTURE' }),
    h('button', { class: 'pl-b sm', title: 'File', onclick: () => { S.queueOpen = true; renderQueue(); } }, ico('list-dashes'))));

  const ring = h('div', { class: 'pl-ring', html:
    '<svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="48" stroke="rgba(233,233,237,.08)"/>' +
    '<circle id="ringArc" cx="50" cy="50" r="48" stroke="#7C5CFF" stroke-dasharray="301.6" stroke-dashoffset="' +
    (301.6 * (1 - pct / 100)).toFixed(1) + '"/></svg>' });

  p.append(h('div', { class: 'pl-body' },
    h('div', { class: 'pl-disc' }, cover(t, 600), ring),
    h('div', { class: 'pl-t', text: t.Name }),
    h('div', { class: 'pl-a', text: artistOf(t) }),
    h('div', { class: 'pl-seek' },
      h('input', { id: 'seek', type: 'range', min: 0, max: 1000, value: Math.round(pct * 10),
        oninput: (e) => { seeking = true; S.pos = (e.target.value / 1000) * (S.dur || 0); paintProgress(); },
        onchange: (e) => { seeking = false; if (S.dur) audio.currentTime = (e.target.value / 1000) * S.dur; } }),
      h('div', { class: 'pl-times' },
        h('span', { id: 'plPos', text: fmt(S.pos) }), h('span', { id: 'plDur', text: fmt(S.dur) }))),
    h('div', { class: 'pl-ctl' },
      h('button', { class: 'pl-b sm' + (S.shuffle ? ' on' : ''), title: 'Aléatoire',
        onclick: () => { S.shuffle = !S.shuffle; toast(S.shuffle ? 'Aléatoire activé' : 'Aléatoire désactivé'); render(); } }, ico('shuffle')),
      h('button', { class: 'pl-b', title: 'Précédent', onclick: prev }, ico('skip-back')),
      h('button', { class: 'pl-play', id: 'plPlay', title: 'Lecture/Pause', onclick: togglePlay }, ico(S.playing ? 'pause' : 'play')),
      h('button', { class: 'pl-b', title: 'Suivant', onclick: () => next(false) }, ico('skip-forward')),
      h('button', { class: 'pl-b sm' + (S.repeat !== 'off' ? ' on' : ''), title: 'Répéter',
        onclick: cycleRepeat }, ico('repeat'), S.repeat === 'one' ? h('span', { class: 'pl-badge', text: '1' }) : null))));

  p.append(h('div', { class: 'pl-bot' },
    h('button', { class: 'pl-b sm', title: 'Favori', onclick: () => favToggle(t) },
      h('i', { class: fmtIconOf(t) })),
    h('button', { class: 'pl-b sm', title: 'Ajouter à une playlist', onclick: () => toast('Playlists — à venir') }, ico('playlist')),
    h('button', { class: 'pl-b sm', title: 'File d\'attente', onclick: () => { S.queueOpen = true; renderQueue(); } },
      ico('queue'), S.queue.length ? h('span', { class: 'pl-badge', text: String(S.queue.length) }) : null)));

  p.classList.remove('in'); void p.offsetWidth; p.classList.add('in');
  paintPlay();
}

function renderQueue() {
  const old = $('.sheet'); if (old) old.remove();
  const s = h('div', { class: 'sheet', onclick: (e) => { if (e.target.classList.contains('sheet')) { S.queueOpen = false; renderQueue(); } } },
    h('div', { class: 'sheet-b' },
      h('div', { class: 'sheet-h' },
        h('div', { class: 'sheet-t', text: 'File d\'attente · ' + plural(S.queue.length, 'morceau') }),
        h('button', { class: 'sheet-x', onclick: () => { S.queueOpen = false; renderQueue(); } }, ico('x'))),
      h('div', { class: 'sheet-body' },
        h('div', { class: 'rows' }, ...S.queue.map((t, i) => {
          const r = trackRowFixed(t, i, S.queue);
          if (i === S.qi) r.classList.add('on');
          return r;
        })))));
  document.body.append(s);
}

function paintProgress() {
  const pct = S.dur ? Math.min(100, (S.pos / S.dur) * 100) : 0;
  const s = $('#seek');
  if (s && !seeking && document.activeElement !== s) s.value = Math.round(pct * 10);
  const pos = $('#plPos'); if (pos) pos.textContent = fmt(S.pos);
  const dur = $('#plDur'); if (dur) dur.textContent = fmt(S.dur);
  const mini = $('#miniP'); if (mini) mini.style.width = pct + '%';
  const art = $('#ringArc');
  if (art) art.setAttribute('stroke-dashoffset', (301.6 * (1 - pct / 100)).toFixed(1));
}
function paintPlay() {
  const b = $('#plPlay'); if (b) b.innerHTML = '<i class="ph ' + (S.playing ? 'fill ' : '') + 'ph-' + (S.playing ? 'pause' : 'play') + '"></i>';
  const mb = $('.mini-btn .ph-pause, .mini-btn .ph-play');
  if (mb) mb.className = 'ph ' + (S.playing ? 'fill ph-pause' : 'ph-play');
}

boot();
