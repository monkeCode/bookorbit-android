// No navigation, progress mutation, model calls or percentage-based boundaries.
// A page boundary means the START of the displayed page, not everything visible on it.
import * as CFI from './foliate/epubcfi.js';
export const CONTEXT_LIMIT = 64000;

function requireValue(value) { if (!value) throw new Error('boundary'); return value; }

function textInRange(doc, range, limit) {
  const root = doc.body ?? doc.documentElement;
  const walker = doc.createTreeWalker(root, 4); // SHOW_TEXT
  let result = '';
  let last = null;
  while (walker.nextNode()) {
    const node = walker.currentNode;
    if (node.parentElement?.closest('script,style,noscript,template,head,[hidden],[aria-hidden="true"]')) continue;
    if (!range.intersectsNode(node)) continue;
    const start = range.startContainer === node ? range.startOffset : 0;
    const end = range.endContainer === node ? range.endOffset : node.length;
    if (end <= start) continue;
    const text = node.data.slice(start, end);
    if (result.length + text.length + 1 > limit) throw new Error('too_long');
    result += text + '\n';
    if (text.trim()) last = { node, start, end: start + text.trimEnd().length };
  }
  return { text: result, last };
}

function wholeRange(doc) {
  const range = doc.createRange();
  range.selectNodeContents(doc.body ?? doc.documentElement);
  return range;
}

function noteAnchor(view, index, doc, last) {
  if (!last) return { noteCfi: null, noteText: null };
  const range = doc.createRange();
  range.setStart(last.node, Math.max(last.start, last.end - 256));
  range.setEnd(last.node, last.end);
  const noteCfi = view.getCFI(index, range);
  // The annotation API caps CFIs at 2,000 characters. Do not invent a shorter one.
  return noteCfi.length <= 2000 ? { noteCfi, noteText: range.toString() } : { noteCfi: null, noteText: null };
}

export async function extractChatContext(view, command) {
  const scope = command.scope;
  requireValue(['book', 'chapter', 'selection'].includes(scope));
  const location = view.lastLocation;
  const boundaryCfi = command.cfi || location?.cfi;
  requireValue(typeof boundaryCfi === 'string' && boundaryCfi.startsWith('epubcfi('));
  const resolved = requireValue(view.resolveCFI(boundaryCfi));
  const endIndex = resolved.index;
  const sections = view.book?.sections;
  requireValue(Number.isInteger(endIndex) && endIndex >= 0 && endIndex < sections?.length && typeof resolved.anchor === 'function');
  const endDoc = await sections[endIndex].createDocument();
  const anchor = requireValue(resolved.anchor(endDoc));
  requireValue(anchor.startContainer && anchor.endContainer && anchor.startContainer.ownerDocument === endDoc);
  // Foliate can recover malformed/stale paths for navigation. Context extraction must
  // not accept that recovery: a round trip must retain both exact text endpoints.
  requireValue(CFI.compare(view.getCFI(endIndex, anchor), boundaryCfi) === 0);

  if (scope === 'selection') {
    requireValue(command.cfi && !anchor.collapsed);
    const { text, last } = textInRange(endDoc, anchor, CONTEXT_LIMIT);
    requireValue(text.trim());
    // A real text range within the selection, including for long excerpts.
    return { scope, boundaryCfi, chapter: String(command.chapter ?? '').slice(0, 1024), text,
      ...noteAnchor(view, endIndex, endDoc, last) };
  }

  // Explicitly selected text is considered read through its end. Toolbar-free chat stops
  // at page start. Resolve again in the immutable section document, never in reflowed DOM.
  const end = anchor.cloneRange();
  end.collapse(!command.cfi);
  let startIndex = 0;
  let chapterStart = null;
  const chapter = command.cfi ? command.chapter : location?.tocItem?.label;
  if (scope === 'chapter') {
    // TOC boundaries, including two chapters in one spine item. Never equate chapter
    // with section index or silently fall back to a whole section.
    const href = command.cfi ? command.chapterHref : location?.tocItem?.href;
    requireValue(typeof href === 'string' && href.length);
    chapterStart = requireValue(await view.book.resolveHref(href));
    startIndex = chapterStart.index;
    requireValue(Number.isInteger(startIndex) && startIndex >= 0 && startIndex <= endIndex);
  }
  let text = '';
  let noteCfi = null;
  let noteText = null;
  for (let index = startIndex; index <= endIndex; index++) {
    const section = sections[index];
    if (section.linear === 'no') continue;
    requireValue(typeof section.createDocument === 'function');
    const doc = index === endIndex ? endDoc : await section.createDocument();
    const range = wholeRange(doc);
    if (index === startIndex && chapterStart?.anchor) {
      const start = chapterStart.anchor(doc);
      if (start !== 0) { // Foliate's section-start anchor is the number zero.
        requireValue(start);
        if (start.startContainer) range.setStart(start.startContainer, start.startOffset);
        else { requireValue(start.ownerDocument === doc); range.setStartBefore(start); }
      }
    }
    if (index === endIndex) {
      // setEnd would collapse an invalid reversed range; reject it instead.
      requireValue(range.comparePoint(end.startContainer, end.startOffset) >= 0);
      range.setEnd(end.startContainer, end.startOffset);
    }
    const extracted = textInRange(doc, range, CONTEXT_LIMIT - text.length);
    text += extracted.text;
    if (extracted.last) {
      ({ noteCfi, noteText } = noteAnchor(view, index, doc, extracted.last));
    }
  }
  requireValue(text.trim());
  return { scope, boundaryCfi, chapter: String(chapter ?? '').slice(0, 1024), text, noteCfi, noteText };
}
