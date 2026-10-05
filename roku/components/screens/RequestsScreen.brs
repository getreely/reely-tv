' The Request tab. Reely's address and session are m.global.reely ({ base, cookie }),
' which MainScene keeps; what's shown is asked for again each time the tab opens.

sub init()
    m.connect = m.top.findNode("connect")
    m.browse = m.top.findNode("browse")
    m.form = m.top.findNode("form")
    m.searchPill = m.top.findNode("search")
    m.rows = m.top.findNode("rows")
    m.note = m.top.findNode("note")
    m.top.findNode("connectTitle").font = Bold_(48)
    m.top.findNode("connectNote").font = Regular_(28)
    m.top.findNode("connectError").font = Regular_(26)
    m.note.font = Regular_(28)
    m.rows.rowLabelFont = Bold_(32)
    m.form.font = Regular_(28)
    m.form.focusedFont = Semibold_(28)
    m.form.observeField("itemSelected", "onForm")
    m.searchPill.observeField("pressed", "onSearchPressed")
    m.rows.observeField("rowItemSelected", "onPicked")
    m.address = ""
    m.query = ""
    m.busy = false
    m.home = invalid
    m.results = invalid
    m.landed = false
end sub

' Reely as this page last knew it: what it set itself (MainScene saves it a moment
' later), else what's kept.
function Reely_() as object
    if m.reely <> invalid then return m.reely
    r = m.global.reely
    if r = invalid then r = {}
    return r
end function

' What the task needs: Reely's address and session, and the Plex sign-ins to use.
function Asking_() as object
    s = Session_()
    r = Reely_()
    return { base: Str_(r.base), cookie: Str_(r.cookie), token: Str_(s.token), accountToken: Str_(s.accountToken) }
end function

sub open()
    render()
end sub

sub render()
    if Str_(Reely_().base) = "" then
        m.browse.visible = false
        m.connect.visible = true
        paintForm()
    else
        m.connect.visible = false
        m.browse.visible = true
        paintSearch()
        load()
    end if
    if m.top.isInFocusChain() then focusIn()
end sub

sub focusIn()
    if m.connect.visible then
        m.form.setFocus(true)
    else if m.rows.content <> invalid and m.rows.content.getChildCount() > 0 then
        m.rows.setFocus(true)
    else
        m.searchPill.setFocus(true)
    end if
end sub

' ------------------------------------------------------------------ Connecting

sub paintForm()
    content = CreateObject("roSGNode", "ContentNode")
    c = content.createChild("ContentNode")
    c.title = "    " + Iif_(m.address = "", "Reely address, like 192.168.1.5:8788", "Reely address:  " + m.address)
    c = content.createChild("ContentNode")
    c.title = "    " + Iif_(m.busy, "Connecting…", "Connect")
    m.form.content = content
end sub

sub onForm()
    if m.form.itemSelected = 1 then
        connect()
        return
    end if
    m.typing = "address"
    openKeyboard("Reely address", m.address)
end sub

sub openKeyboard(title as string, text as string)
    dialog = CreateObject("roSGNode", "KeyboardDialog")
    dialog.title = title
    dialog.text = text
    dialog.buttons = ["OK", "Cancel"]
    dialog.observeField("buttonSelected", "onTyped")
    m.top.getScene().dialog = dialog
    m.dialog = dialog
    Trace_("typing " + m.typing)
end sub

sub onTyped()
    ok = m.dialog.buttonSelected = 0
    text = m.dialog.text.Trim()
    m.dialog.close = true
    m.dialog = invalid
    if m.typing = "address" then
        if ok then m.address = text
        paintForm()
        m.form.setFocus(true)
    else
        if ok then
            m.query = text
            paintSearch()
            if m.query = "" then
                m.results = invalid
                paintRows()
            else
                m.note.text = "Searching…"
                Ask_("reelySearch", { reely: Asking_(), query: m.query })
            end if
        end if
        m.searchPill.setFocus(true)
    end if
end sub

sub connect()
    if m.busy then return
    err = m.top.findNode("connectError")
    if not Reely_IsValid(m.address) then
        err.text = "Enter Reely's address, like reely.example.com or 192.168.1.20:8788."
        return
    end if
    err.text = ""
    m.busy = true
    paintForm()
    a = Asking_()
    a.base = Reely_Normalize(m.address)
    a.cookie = ""
    m.connecting = a.base
    Ask_("reelySignIn", { reely: a })
end sub

' ------------------------------------------------------------------ Browsing

sub paintSearch()
    m.searchPill.labels = [Iif_(m.query = "", "Search movies and shows to request", "Search:  " + m.query)]
end sub

sub onSearchPressed()
    m.typing = "query"
    openKeyboard("Search movies and shows to request", m.query)
end sub

sub load()
    if Str_(Reely_().base) = "" then return
    if m.home = invalid then m.note.text = "Loading…"
    Ask_("reelyHome", { reely: Asking_(), libraries: ShownLibraries_("") })
end sub

' Your requests, then Reely's rows less what's in the library; or what a search found.
sub paintRows()
    rows = []
    m.note.text = ""
    if m.results <> invalid then
        titles = []
        for each t in m.results
            titles.Push(Shown_(t))
        end for
        if titles.Count() = 0 then m.note.text = "Nothing matched " + Chr(34) + m.query + Chr(34) + "."
        rows.Push({ title: "Results", items: titles, plain: true })
    else if m.home <> invalid then
        mine = []
        seen = {}
        for each r in m.home.mine
            key = Reely_Key(r.title)
            if not seen.DoesExist(key) then
                seen[key] = true
                mine.Push(Shown_(r.title))
            end if
        end for
        rows.Push({ title: "Your requests", items: mine, plain: true })
        for each row in Reely_ShownRows(m.home.rows, m.home.marks, m.home.mine)
            items = []
            names = []
            for each t in row.titles
                items.Push(Shown_(t))
                names.Push(t.title)
            end for
            Trace_("reely row " + row.id + ": " + Join_(names, ", "))
            rows.Push({ title: row.title, items: items, plain: true })
        end for
    end if
    m.rows.translation = Iif_(m.note.text <> "", [96, 150], [96, 90])
    ShowRows_(m.rows, rows)
    hasRows = m.rows.content <> invalid and m.rows.content.getChildCount() > 0
    ' The first time there are posters, the cursor goes to the first, as the other apps land.
    if m.top.isInFocusChain() and hasRows and (not m.searchPill.hasFocus() or not m.landed) then
        m.landed = true
        m.rows.setFocus(true)
    end if
end sub

' A title with its line: the year, and where it's got to.
function Shown_(t as object) as object
    badge = ""
    if m.home <> invalid then badge = Reely_BadgeFor(t, m.home.marks, m.home.mine)
    year = ""
    if t.year > 0 then year = t.year.ToStr()
    out = {}
    out.Append(t)
    out.caption = Join_([year, badge], "  ·  ")
    return out
end function

sub onPicked()
    t = SelectedRowItem_(m.rows)
    mine = []
    if m.home <> invalid then mine = m.home.mine
    if t <> invalid then m.top.go = { name: "requestTitle", title: t, mineRequests: mine }
end sub

sub answered(r as object)
    a = r.answer
    if r.op = "reelySignIn" then
        m.busy = false
        if a.problem <> "" then
            m.top.findNode("connectError").text = a.problem
            paintForm()
            return
        end if
        Trace_("reely connected")
        m.reely = { base: m.connecting, cookie: a.cookie }
        m.top.go = { name: "reely", reely: m.reely }
        render()
    else if r.op = "reelyHome" then
        if a.problem <> "" then
            Trace_("reely problem " + a.problem)
            m.note.text = a.problem
            return
        end if
        keep(a.cookie)
        m.home = a
        Trace_("reely rows " + a.rows.Count().ToStr())
        paintRows()
    else if r.op = "reelySearch" then
        if a.problem <> "" then
            m.note.text = a.problem
            return
        end if
        keep(a.cookie)
        m.results = a.results
        Trace_("reely results " + a.results.Count().ToStr())
        paintRows()
    end if
end sub

' A new session, kept for the next ask.
sub keep(cookie as string)
    r = Reely_()
    if cookie <> "" and cookie <> Str_(r.cookie) then
        m.reely = { base: r.base, cookie: cookie }
        m.top.go = { name: "reely", reely: m.reely }
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.browse.visible then
        if key = "down" and m.searchPill.hasFocus() and m.rows.content <> invalid and m.rows.content.getChildCount() > 0 then
            m.rows.setFocus(true)
            return true
        else if key = "up" and m.rows.hasFocus() and m.rows.rowItemFocused[0] = 0 then
            m.searchPill.setFocus(true)
            return true
        end if
    end if
    return false
end function
