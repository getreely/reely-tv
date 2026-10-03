' Reely for Roku: one scene, which does the rest.
sub Main(args as dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.SetMessagePort(port)
    ' Where Plex's sign-in and server list are. Only a debug build, for its tests, can be
    ' pointed anywhere else: a release always signs in at plex.tv.
    plexTv = "https://plex.tv"
    #if DEBUG
        if args <> invalid and args.plexTv <> invalid then plexTv = args.plexTv
    #end if
    screen.getGlobalNode().addFields({ plexTv: plexTv })
    scene = screen.CreateScene("MainScene")
    screen.Show()
    while true
        msg = Wait(0, port)
        if type(msg) = "roSGScreenEvent" and msg.IsScreenClosed() then return
    end while
end sub
