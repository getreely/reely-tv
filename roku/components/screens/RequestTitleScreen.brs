' A title's page in Requests. Seasons start all chosen; a pill toggles each. Asking sends
' the title, its seasons (a show) and the library, and the answer is said on the page.

sub init()
    m.top.findNode("title").font = Bold_(60)
    m.top.findNode("facts").font = Regular_(28)
    m.top.findNode("summary").font = Regular_(28)
    m.top.findNode("note").font = Regular_(28)
    m.top.findNode("librariesTitle").font = Semibold_(22)
    m.top.findNode("seasonsTitle").font = Semibold_(22)
    m.actions = m.top.findNode("actions")
    m.libraries = m.top.findNode("libraries")
    m.seasons = m.top.findNode("seasons")
    m.actions.observeField("pressed", "onAsk")
    m.libraries.observeField("pressed", "onLibrary")
    m.seasons.observeField("pressed", "onSeason")
    m.detail = invalid
    m.sending = false
    m.outcome = ""
end sub

function Asking_() as object
    s = Session_()
    r = m.global.reely
    return { base: Str_(r.base), cookie: Str_(r.cookie), token: Str_(s.token), accountToken: Str_(s.accountToken) }
end function

sub open()
    m.t = m.top.route.title
    m.mine = Arr_(m.top.route.mineRequests)
    m.top.findNode("title").text = m.t.title
    m.top.findNode("summary").text = m.t.overview
    m.top.findNode("backdrop").uri = Str_(m.t.poster)
    m.top.findNode("note").text = "Loading…"
    paintFacts()
    Ask_("reelyDetail", { reely: Asking_(), title: m.t })
end sub

sub paintFacts()
    d = m.detail
    parts = []
    if m.t.year > 0 then parts.Push(m.t.year.ToStr())
    parts.Push(Iif_(m.t.kind = "show", "Show", "Movie"))
    if d <> invalid then
        if d.runtime > 0 then parts.Push(Plex_Duration(d.runtime * 60000))
        if d.status <> "" then parts.Push(d.status)
        genres = []
        for each g in d.genres
            if genres.Count() < 3 then genres.Push(g)
        end for
        if genres.Count() > 0 then parts.Push(Join_(genres, ", "))
    end if
    m.top.findNode("facts").text = Join_(parts, "  ·  ")
end sub

' Whether it can be asked for, and the words round it.
sub paint()
    d = m.detail
    held = d.inLibraries.Count()
    if m.places <> invalid then
        m.addable = Reely_LibrariesFor(m.places, d.title, Reely_HoldingAll(d))
        canAsk = m.addable.Count() > 0
    else
        m.addable = []
        canAsk = held = 0
    end if
    ' Of a show partly here, only the seasons it hasn't got are offered.
    m.offered = Reely_SeasonsLeft(d, m.libraryId)
    m.here = []
    asked = Reely_AskedIn(d, m.libraryId)
    for each s in d.seasons
        if asked.DoesExist(s.number.ToStr()) then m.here.Push(s)
    end for
    notes = []
    if canAsk and m.here.Count() > 0 and m.offered.Count() > 0 then
        notes.Push(Iif_(m.here.Count() = 1, m.here[0].name, m.here.Count().ToStr() + " seasons") + " here already. Pick more to ask for.")
    else if not canAsk then
        notes.Push(Iif_(held > 1, "Already in " + held.ToStr() + " libraries: there's nowhere left for it to go.", "Already in your library."))
    else if held = 1 then
        notes.Push("Already in a library. It can go in another as well.")
    else if held > 1 then
        notes.Push("Already in " + held.ToStr() + " libraries. It can go in another as well.")
    end if
    status = ""
    for each r in m.mine
        if status = "" and Reely_Key(r.title) = Reely_Key(m.t) then status = r.status
    end for
    words = { pending: "You asked for this. It's waiting to be approved.", approved: "You asked for this. It's been approved and is on its way.", denied: "You asked for this. It was declined." }
    ' What was just said about asking, else where an earlier ask has got to.
    if m.outcome <> "" then
        notes.Push(m.outcome)
    else if words.DoesExist(status) then
        notes.Push(words[status])
    end if
    m.top.findNode("note").text = Join_(notes, "  ")
    m.canAsk = canAsk
    m.actions.visible = canAsk
    if canAsk then m.actions.labels = [askLabel()]
    libs = canAsk and m.addable.Count() > 1
    m.libraries.visible = libs
    m.top.findNode("librariesTitle").visible = libs
    if libs then
        labels = []
        on = []
        for each l in m.addable
            labels.Push(l.name)
            on.Push(l.id = m.libraryId)
        end for
        m.libraries.labels = labels
        m.libraries.on = on
    end if
    seasons = canAsk and m.t.kind = "show" and m.offered.Count() > 0
    m.seasons.visible = seasons
    m.top.findNode("seasonsTitle").visible = seasons
    if seasons then
        labels = []
        on = []
        for each s in m.offered
            labels.Push(s.name)
            on.Push(m.chosen.DoesExist(s.number.ToStr()))
        end for
        m.seasons.labels = labels
        m.seasons.on = on
    end if
    ' The seasons' row sits up where the libraries' would be when there's no choice of library.
    m.top.findNode("seasonsTitle").translation = Iif_(libs, [96, 730], [96, 590])
    m.seasons.translation = Iif_(libs, [96, 770], [96, 630])
end sub

function askLabel() as string
    verb = "Request"
    if m.places <> invalid and m.places.adds then verb = "Add"
    if m.sending then return Iif_(verb = "Add", "Adding…", "Requesting…")
    if m.t.kind <> "show" or m.offered.Count() = 0 then return verb
    n = pickedCount()
    if n = m.offered.Count() and m.here.Count() = 0 then return verb + " all seasons"
    if n = m.offered.Count() and n > 1 then return verb + " the other " + n.ToStr() + " seasons"
    if n = 1 then return verb + " 1 season"
    return verb + " " + n.ToStr() + " seasons"
end function

' How many of the seasons offered are picked.
function pickedCount() as integer
    n = 0
    for each s in m.offered
        if m.chosen.DoesExist(s.number.ToStr()) then n = n + 1
    end for
    return n
end function

sub onLibrary()
    m.libraryId = m.addable[m.libraries.pressed].id
    paint()
end sub

sub onSeason()
    n = m.offered[m.seasons.pressed].number.ToStr()
    if m.chosen.DoesExist(n) then m.chosen.Delete(n) else m.chosen[n] = true
    paint()
end sub

sub onAsk()
    if m.sending or not m.canAsk then return
    seasons = invalid
    if m.t.kind = "show" and m.offered.Count() > 0 then
        if pickedCount() = 0 then
            m.outcome = "Choose at least one season."
            paint()
            return
        end if
        ' Of a show partly here, the seasons picked: Reely keeps the ones already asked for.
        seasons = []
        for each s in m.offered
            if m.chosen.DoesExist(s.number.ToStr()) then seasons.Push(s.number)
        end for
    end if
    m.sending = true
    m.outcome = ""
    paint()
    Ask_("reelyRequest", { reely: Asking_(), title: m.detail.title, seasons: seasons, libraryId: Int(Num_(m.libraryId)) })
end sub

sub answered(r as object)
    a = r.answer
    if r.op = "reelyDetail" then
        if a.problem <> "" then
            m.top.findNode("note").text = a.problem
            return
        end if
        m.detail = a.detail
        m.places = a.places
        if Str_(a.detail.title.overview) <> "" then m.top.findNode("summary").text = a.detail.title.overview
        if a.detail.backdrop <> "" then m.top.findNode("backdrop").uri = a.detail.backdrop
        m.chosen = {}
        for each s in a.detail.seasons
            m.chosen[s.number.ToStr()] = true
        end for
        m.libraryId = 0
        if m.places <> invalid then
            best = Reely_PreferredLibrary(m.places, Reely_LibrariesFor(m.places, a.detail.title, Reely_HoldingAll(a.detail)))
            if best <> invalid then m.libraryId = best.id
        end if
        paintFacts()
        paint()
        Trace_("reely title " + Iif_(m.canAsk, "can ask", "can't ask"))
        if m.top.isInFocusChain() then focusIn()
    else if r.op = "reelyRequest" then
        m.sending = false
        if a.outcome = "sent" then
            m.outcome = Iif_(a.approved, "Added. It'll be in your library once it's downloaded.", "Requested. You'll see it here once it's approved.")
            m.mine.Push({ id: 0, title: m.t, status: Iif_(a.approved, "approved", "pending"), seasons: invalid })
            m.top.go = { name: "refreshRequests" }
        else if a.outcome = "already" then
            m.outcome = "That's been asked for already."
        else
            m.outcome = a.message
        end if
        Trace_("reely asked " + a.outcome)
        paint()
    end if
end sub

sub focusIn()
    if m.actions.visible then
        m.actions.setFocus(true)
    else
        m.top.setFocus(true)
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    order = []
    for each n in [m.actions, m.libraries, m.seasons]
        if n.visible then order.Push(n)
    end for
    at = -1
    for i = 0 to order.Count() - 1
        if order[i].hasFocus() then at = i
    end for
    if key = "down" and at >= 0 and at < order.Count() - 1 then
        order[at + 1].setFocus(true)
        return true
    else if key = "up" and at > 0 then
        order[at - 1].setFocus(true)
        return true
    end if
    return false
end function
