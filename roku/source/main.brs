' Reely for Roku: one scene, which does the rest.
sub Main(args as dynamic)
    #if DEBUG
        ' The screensaver on its own, for its test.
        if args <> invalid and Str_(args.screensaver) = "1" then
            RunScreenSaver()
            return
        end if
    #end if
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.SetMessagePort(port)
    ' Where Plex's sign-in and server list are. Only a debug build, for its tests, can be
    ' pointed anywhere else: a release always signs in at plex.tv.
    plexTv = "https://plex.tv"
    discover = "https://discover.provider.plex.tv"
    #if DEBUG
        if args <> invalid and args.plexTv <> invalid then plexTv = args.plexTv
        if args <> invalid and args.discover <> invalid then discover = args.discover
    #end if
    screen.getGlobalNode().addFields({ plexTv: plexTv, discover: discover })
    scene = screen.CreateScene("MainScene")
    screen.Show()
    while true
        msg = Wait(0, port)
        if type(msg) = "roSGScreenEvent" and msg.IsScreenClosed() then return
    end while
end sub

' Reely's own screensaver, which the Roku starts when the remote's been put down while
' Reely is open (screensaver_private in the manifest): the library's artwork and the time.
sub RunScreenSaver()
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.SetMessagePort(port)
    screen.CreateScene("Screensaver")
    screen.Show()
    while true
        msg = Wait(0, port)
        if type(msg) = "roSGScreenEvent" and msg.IsScreenClosed() then return
    end while
end sub
