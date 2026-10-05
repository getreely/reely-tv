' Geist at a size, as the other apps use it. Each size is made once for the whole app and
' shared: making a font is slow on a Roku, and a poster made four of its own, so moving to a
' row of new posters held up the remote for seconds while their fonts were made.

function FontOf_(uri as string, size as integer) as object
    key = uri + "@" + size.ToStr()
    if m.fonts_ = invalid then m.fonts_ = {}
    f = m.fonts_[key]
    if f <> invalid then return f
    shelf = invalid
    if m.global <> invalid then
        ' Made by whichever component asks first: the tabs are made before the scene is.
        if not m.global.hasField("fontShelf") then m.global.addFields({ fontShelf: CreateObject("roSGNode", "Node") })
        shelf = m.global.fontShelf
    end if
    if shelf <> invalid then f = shelf.findNode(key)
    if f = invalid then
        f = CreateObject("roSGNode", "Font")
        f.id = key
        f.uri = uri
        f.size = size
        if shelf <> invalid then shelf.appendChild(f)
    end if
    m.fonts_[key] = f
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
