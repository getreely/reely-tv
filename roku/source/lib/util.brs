' Small things the rest leans on: text from whatever JSON gave, and a stable sort.

' Every name a number goes by: plain or boxed, as Roku's firmware and JSON reader give them.
function IsWhole_(t as string) as boolean
    return t = "Integer" or t = "roInt" or t = "roInteger" or t = "LongInteger" or t = "roLongInteger"
end function

function IsFraction_(t as string) as boolean
    return t = "Float" or t = "roFloat" or t = "Double" or t = "roDouble" or t = "Intrinsic Double"
end function

function Str_(v as dynamic) as string
    if v = invalid then return ""
    t = type(v)
    if t = "String" or t = "roString" then return v
    if IsWhole_(t) then return v.ToStr()
    if IsFraction_(t) then
        ' Whole numbers as whole numbers: 1999, not 1999.0.
        if v = Int(v) then return Int(v).ToStr()
        return Str(v).Trim()
    end if
    if t = "Boolean" or t = "roBoolean" then
        if v then return "true"
        return "false"
    end if
    return ""
end function

function Num_(v as dynamic) as dynamic
    if v = invalid then return 0
    t = type(v)
    if IsWhole_(t) or IsFraction_(t) then return v
    s = Str_(v).Trim()
    if s = "" then return 0
    ' A whole number exactly: Val reads into a single-precision float, which can't hold a
    ' time in seconds (1759523400 comes back as 1759523456).
    if CreateObject("roRegex", "^-?\d{1,10}$", "").IsMatch(s) then return s.ToInt()
    return Val(s)
end function

' A whole number greater than nought, else invalid.
function Pos_(v as dynamic) as dynamic
    n = Num_(v)
    if n > 0 then return Int(n)
    return invalid
end function

function Arr_(v as dynamic) as object
    if v <> invalid and (type(v) = "roArray") then return v
    return []
end function

function Bool_(v as dynamic) as boolean
    if v = invalid then return false
    if type(v) = "Boolean" or type(v) = "roBoolean" then return v
    s = LCase(Str_(v))
    return s = "true" or s = "1"
end function

' Equal items keep their order: Roku's own sort doesn't promise it.
function StableSort_(list as object, before as function) as object
    out = []
    for each item in list
        out.Push(item)
    end for
    ' Insertion sort: lists here are a few hundred long at most.
    for i = 1 to out.Count() - 1
        current = out[i]
        j = i - 1
        while j >= 0 and before(current, out[j])
            out[j + 1] = out[j]
            j = j - 1
        end while
        out[j + 1] = current
    end for
    return out
end function

' As roUrlTransfer's Escape does it (all but letters, digits and - _ . ~), without making one:
' a Roku can't make one on the scene's thread, where every picture's address is put
' together, and each try held up the remote before it failed.
function UrlEncode_(s as string) as string
    return s.EncodeUriComponent().Replace("!", "%21").Replace("'", "%27").Replace("(", "%28").Replace(")", "%29").Replace("*", "%2A")
end function

function Join_(parts as object, sep as string) as string
    out = ""
    for each p in parts
        if p <> invalid and p <> "" then
            if out <> "" then out = out + sep
            out = out + p
        end if
    end for
    return out
end function

' Both sides are worked out before the choice: never one that only holds when [test] does.
function Iif_(test as boolean, yes as dynamic, no as dynamic) as dynamic
    if test then return yes
    return no
end function

' What the end-to-end test follows: said in a debug build only.
sub Trace_(what as string)
    #if DEBUG
        print "TRACE "; what
    #end if
end sub

' ---------------------------------------------------------------- The accent

' The colours things can be marked in, as the other apps offer them: Reely's blue unless
' another is picked. Each with what's written on it, white or black, whichever reads.
function Accent_Choices() as object
    return [
        { id: "blue", label: "Blue", color: "0x2E6BFFFF", on: "0xFFFFFFFF" },
        { id: "red", label: "Red", color: "0xFF5E69FF", on: "0x08090BFF" },
        { id: "purple", label: "Purple", color: "0x8B5CF6FF", on: "0xFFFFFFFF" },
        { id: "pink", label: "Pink", color: "0xEC4899FF", on: "0xFFFFFFFF" },
        { id: "orange", label: "Orange", color: "0xFF8A3DFF", on: "0x08090BFF" },
        { id: "gold", label: "Gold", color: "0xF5C542FF", on: "0x08090BFF" },
        { id: "teal", label: "Teal", color: "0x14B8A6FF", on: "0x08090BFF" }
    ]
end function

' The choice for an id; the blue for anything unknown.
function Accent_Of(id as dynamic) as object
    choices = Accent_Choices()
    for each a in choices
        if a.id = Str_(id) then return a
    end for
    return choices[0]
end function

' The colour picked in Settings, as the scene keeps it for every component.
function Accent_() as string
    g = m.global
    if g <> invalid and g.accent <> invalid and g.accent <> "" then return g.accent
    return "0x2E6BFFFF"
end function

' What's written on it.
function AccentOn_() as string
    g = m.global
    if g <> invalid and g.accentOn <> invalid and g.accentOn <> "" then return g.accentOn
    return "0xFFFFFFFF"
end function
