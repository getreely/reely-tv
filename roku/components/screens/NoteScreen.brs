' A page with only something to say.

sub open()
    m.top.findNode("title").font = Bold_(54)
    m.top.findNode("text").font = Regular_(30)
    m.top.findNode("title").text = m.top.route.title
    m.top.findNode("text").text = m.top.route.text
end sub
