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

function UrlEncode_(s as string) as string
    return CreateObject("roUrlTransfer").Escape(s)
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
