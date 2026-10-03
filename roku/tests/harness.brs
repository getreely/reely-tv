' A test is a sub that calls Expect; each Expect prints PASS or FAIL with what differed.
sub Expect(name as string, actual as dynamic, expected as dynamic)
    a = FormatJson(actual)
    e = FormatJson(expected)
    if a = e then
        print "PASS " + name
    else
        print "FAIL " + name + ": got " + a + ", wanted " + e
    end if
end sub

function Titles_(list as object) as object
    out = []
    for each i in list
        out.Push(i.ratingKey)
    end for
    return out
end function

function Ep_(key as string, watched as boolean, offset as integer, season as dynamic) as object
    viewCount = 0
    if watched then viewCount = 1
    return Plex_ParseItem({ ratingKey: key, title: key, type: "episode", viewCount: viewCount, viewOffset: offset, duration: 1000000, parentIndex: season, grandparentRatingKey: "show" }, "http://a")
end function
