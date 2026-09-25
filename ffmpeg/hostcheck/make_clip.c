// One second of a 440 Hz tone on all six channels of a 5.1 layout, encoded as E-AC3.
// Written as frames, each preceded by its length as four little-endian bytes, which is
// how the test feeds them to the decoder one access unit at a time.
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <math.h>
#include <stdio.h>

static void write_packet(FILE *out, AVPacket *pkt) {
    uint32_t n = (uint32_t)pkt->size;
    fwrite(&n, 4, 1, out);
    fwrite(pkt->data, 1, pkt->size, out);
}

int main(int argc, char **argv) {
    const AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_EAC3);
    AVCodecContext *c = avcodec_alloc_context3(codec);
    c->sample_rate = 48000;
    c->sample_fmt = AV_SAMPLE_FMT_FLTP;
    c->bit_rate = 384000;
    AVChannelLayout layout = AV_CHANNEL_LAYOUT_5POINT1;
    av_channel_layout_copy(&c->ch_layout, &layout);
    if (avcodec_open2(c, codec, NULL) < 0) return 1;

    FILE *out = fopen(argv[1], "wb");
    AVFrame *frame = av_frame_alloc();
    frame->nb_samples = c->frame_size;
    frame->format = c->sample_fmt;
    av_channel_layout_copy(&frame->ch_layout, &c->ch_layout);
    av_frame_get_buffer(frame, 0);
    AVPacket *pkt = av_packet_alloc();

    long t = 0;
    for (int f = 0; f < 48000 / c->frame_size; f++) {
        av_frame_make_writable(frame);
        for (int ch = 0; ch < 6; ch++) {
            float *s = (float *)frame->data[ch];
            for (int i = 0; i < c->frame_size; i++) s[i] = 0.5f * sinf(2 * M_PI * 440 * (t + i) / 48000.0f);
        }
        t += c->frame_size;
        frame->pts = t;
        avcodec_send_frame(c, frame);
        while (avcodec_receive_packet(c, pkt) == 0) { write_packet(out, pkt); av_packet_unref(pkt); }
    }
    avcodec_send_frame(c, NULL);
    while (avcodec_receive_packet(c, pkt) == 0) { write_packet(out, pkt); av_packet_unref(pkt); }
    fclose(out);
    return 0;
}
