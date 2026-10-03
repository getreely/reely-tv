' Geist at a size, as the other apps use it.

function FontOf_(uri as string, size as integer) as object
    f = CreateObject("roSGNode", "Font")
    f.uri = uri
    f.size = size
    return f
end function

function Bold_(size as integer) as object
    return FontOf_("pkg:/fonts/geist_bold.ttf", size)
end function

function Regular_(size as integer) as object
    return FontOf_("pkg:/fonts/geist_regular.ttf", size)
end function

function Semibold_(size as integer) as object
    return FontOf_("pkg:/fonts/geist_semibold.ttf", size)
end function
