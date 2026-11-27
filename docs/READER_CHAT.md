# In-reader AI chat

Open **Settings → AI connection**. Keep BookOrbit as the primary route if desired,
then enable the additional direct provider and configure its endpoint, model and
credentials. Alternatively choose the direct primary route. Saving settings does
not call a model. Open the speech-bubble button in the app text reader, or Reading
Help on a selection.

## Routes

- Persistent chat always uses the configured direct provider: OpenAI, Gemini,
  OpenRouter, Ollama or an OpenAI-compatible endpoint. Stock BookOrbit v3.10.0 has
  no arbitrary-question/multi-turn chat endpoint.
- A selection also offers **Short Reading Help (server/provider)**. This retains
  the fixed server scenarios. The optional fallback setting uses the direct
  provider only after a failed **content-free availability check**, with the
  recipient displayed before confirmation. Authentication failure does not fall
  back. No submitted model request is replayed to a second recipient.
- Photo search and Story Guide remain server-only. Enabling the extra provider
  does not add direct equivalents or change server/device trust policies.

## Context and spoilers

Choose the book so far, chapter so far, or selected text. Book/chapter context
stops at the **start of the displayed page**, not the end of its visible text.
Chapter starts resolve from the TOC, including fragment anchors within one spine
item. Missing, reversed, recovered or unresolved boundaries fail closed. The
context collector does not navigate, write progress, complete chapters or load
later sections. Explicit selection includes exactly the selected range.

Review the extracted text and character count before confirming. The context
snapshot stays fixed for the conversation, including after reading further or
backwards. **New chat** explicitly deletes that conversation and selects a new
boundary; saved notes remain. Each exact edition/revision keeps its own latest
conversation. This is not a multi-conversation browser or server chat sync.

An identified local text copy is required for persistent chat. EPUB, FB2 and
MOBI-family copies use a SHA-256 of their bytes. Streaming EPUB resources cannot
currently be requested with an immutable revision precondition, so streaming
offers selected-text short help only; download the edition for persistent chat.
PDF, comics and the embedded server reader do not gain chat in this slice.

Context is capped at 64,000 UTF-16 characters. Context plus conversation is capped
at 128,000 characters and 40 messages, reserving room for a complete answer before
submission. Oversized input is rejected, never silently summarized or trimmed.
These are client safety bounds, not a tokenizer: the configured model must support
the request and may reject it. There is no retrieval/indexing or automatic summary
of a long book. The prompt excludes outside plot knowledge, but a model that knows
the book can still produce spoilers; this is not a spoiler guarantee.
Provider-side truncation is outside the client contract; in particular, configure
an adequate model context window on an Ollama server rather than assuming its
default window can hold the supplied source and history.

## Persistence, cancellation and notes

The context and messages are kept in device-encrypted, backup-excluded storage,
scoped to server/account/book/file/content revision. Logout/login invalidates the
active authorization lease without relabeling stored history. Every request needs
confirmation of provider/model/endpoint and transmission of context/history.

One confirmed request makes one one-shot provider POST, with no redirects,
automatic retry, provider fallback, BookOrbit bearer token or cookies. Closing the
sheet, backgrounding the activity, changing credentials or changing account cancels
the client call. The provider may nevertheless have processed/billed a submitted
request. An unanswered turn is persisted before submission; reopening never
resubmits it. **Send again** requires new consent; it is not delivery recovery.

Markdown renders as selectable native text: emphasis, headings, lists, quotes and
code. HTML is literal text, links are inert and images do not fetch remote content.

**Save answer to Notes** queues a normal highlight/note using a real text range
within the supplied context and the existing annotation outbox. It never invents
a CFI or overwrites an existing note. The save intent is committed before enqueueing:
an interrupted save is shown as requested/uncertain and cannot blindly create a
second annotation. That conservative crash window can require checking Notes;
"requested" is not a claim of successful server delivery. Notes are not encrypted
chat sync: normal BookOrbit note synchronization applies.
If a note CFI exceeds the server limit, saving is unavailable rather than using a
guessed shorter anchor. No chapter title is inferred for an anchor in earlier text.

## Acceptance

JVM tests exercise provider wire shapes, history persistence/ownership, explicit
retry, context/history limits, route confirmation and note-save reservation.
`ReaderChatContextTest` exercises real WebView DOM Range and production Foliate
CFIs on synthetic text, including page starts, intra-section chapter boundaries,
unread-section exclusion and oversized/invalid boundaries. Provider credentials,
real book submissions and billable inference are not part of these tests.

Local acceptance on November 27, 2026:
- Both flavors: lint, APK assembly and 643 JVM tests per flavor passed.
- 31 Node reader checks and 20 Python development/policy checks passed.
- `ReaderChatContextTest` (10 DOM/CFI assertions), `ReaderChatUiTest` (real direct
  HTTP adapter, history, Markdown and one queued note across reopen) and all three
  `CustomAiConnectionUiTest` scenarios passed three consecutive emulator runs each.
- The initial standalone WebView test harness timed out; serving its synthetic
  document through the asset loader in a resumed activity fixed module execution.
  The passing runs use production context/CFI modules, not replacement extraction.

This does not validate paid provider inference, a particular real book, physical
hardware, server note delivery or immutable streaming-context support.
