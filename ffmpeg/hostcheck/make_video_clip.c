// Two seconds of 720 by 480 MPEG-4 part 2, the way an old DVD rip is made: B-frames,
// quarter-pixel motion, pixels narrower than they're tall (8:9, so it's shown 4:3). The
// top left is red and the top right blue, so a decoder that mixes the colour planes up
// shows; a bar moves across the bottom, so pictures depend on each other.
// Written as packets in the order they're stored, each preceded by its length as four
// little-endian bytes, which is how an AVI hands them over.
#include <libavcodec/avcodec.h>
#include <stdio.h>
#include <string.h>

#define W 720
#define H 480
#define FRAMES 60

static void write_packet(FILE *out, AVPacket *pkt) {
    uint32_t n = (uint32_t)pkt->size;
    fwrite(&n, 4, 1, out);
    fwrite(pkt->data, 1, pkt->size, out);
}

static void fill(AVFrame *f, int x0, int y0, int x1, int y1, int y, int u, int v) {
    for (int r = y0; r < y1; r++) memset(f->data[0] + r * f->linesize[0] + x0, y, x1 - x0);
    for (int r = y0 / 2; r < y1 / 2; r++) {
        memset(f->data[1] + r * f->linesize[1] + x0 / 2, u, (x1 - x0) / 2);
        memset(f->data[2] + r * f->linesize[2] + x0 / 2, v, (x1 - x0) / 2);
    }
}

int main(int argc, char **argv) {
    const AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_MPEG4);
    AVCodecContext *c = avcodec_alloc_context3(codec);
    c->width = W;
    c->height = H;
    c->pix_fmt = AV_PIX_FMT_YUV420P;
    c->time_base = (AVRational){1001, 30000};
    c->framerate = (AVRational){30000, 1001};
    c->sample_aspect_ratio = (AVRational){8, 9};
    c->bit_rate = 1000000;
    c->gop_size = 12;
    c->max_b_frames = 2;
    c->flags |= AV_CODEC_FLAG_QPEL;
    if (avcodec_open2(c, codec, NULL) < 0) return 1;

    FILE *out = fopen(argv[1], "wb");
    AVFrame *frame = av_frame_alloc();
    frame->format = c->pix_fmt;
    frame->width = W;
    frame->height = H;
    av_frame_get_buffer(frame, 0);
    AVPacket *pkt = av_packet_alloc();

    for (int i = 0; i < FRAMES; i++) {
        av_frame_make_writable(frame);
        fill(frame, 0, 0, W / 2, H / 2, 81, 90, 240);      // red
        fill(frame, W / 2, 0, W, H / 2, 41, 240, 110);     // blue
        fill(frame, 0, H / 2, W, H, 128, 128, 128);        // grey
        int x = (i * 8) % (W - 64);
        fill(frame, x & ~1, H / 2 + 64, (x & ~1) + 64, H - 64, 235, 128, 128);  // a white bar, moving
        frame->pts = i;
        avcodec_send_frame(c, frame);
        while (avcodec_receive_packet(c, pkt) == 0) { write_packet(out, pkt); av_packet_unref(pkt); }
    }
    avcodec_send_frame(c, NULL);
    while (avcodec_receive_packet(c, pkt) == 0) { write_packet(out, pkt); av_packet_unref(pkt); }
    fclose(out);
    return 0;
}
