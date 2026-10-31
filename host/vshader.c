/* Direct3D 8 vertex shaders (vs.1.1) and vertex declarations.
 *
 * The declaration says how stream data maps onto input registers v0-v15, the shader runs per vertex,
 * and its outputs (oPos in clip space, oD0/oD1, oT0-oT7) feed the fixed-function pixel stage. Each
 * shader is decoded once into a compact instruction list, which either runs here on the CPU (vs_exec)
 * or is translated to a WGSL vertex function (vs_to_wgsl) so the GPU runs it; then the CPU only
 * fetches the input registers (vs_fetch). */
#include "vshader.h"

#include <math.h>
#include <stdarg.h>
#include <stdlib.h>

enum { RT_TEMP = 0, RT_INPUT = 1, RT_CONST = 2, RT_ADDR = 3, RT_RASTOUT = 4, RT_ATTROUT = 5, RT_TEXOUT = 6 };

/* idx: the operand's slot in the register file (VsCtx.f), resolved at decode time; for relative constant
   addressing it is resolved per use from num + a0 */
typedef struct { uint8_t type, rel, neg, ident, swz[4]; uint16_t num, idx; } Src;
typedef struct { uint16_t op; uint8_t dtype, dmask; uint16_t dnum, didx; uint8_t nsrc; Src s[3]; } Insn;

typedef struct VsProgram {
    Insn *code; uint32_t n;
    /* declaration: input register sources */
    struct { uint8_t used, stream, type; uint16_t offset; } in[16];
    uint8_t used_in[16]; int nused;                /* the used input registers, in order */
    float defs[96][4]; uint8_t has_def[96];        /* constants from the declaration and `def` */
    int any_def;
    int fixed_function;                            /* declaration without a program */
    int ok;
} VsProgram;

/* operand count (destination + sources) per vs.1.1 opcode */
static int nparams(uint32_t op) {
    switch (op) {
    case 0: return 0;
    case 1: case 6: case 7: case 14: case 15: case 16: case 19: case 78: case 79: return 2;
    case 2: case 3: case 5: case 8: case 9: case 10: case 11: case 12: case 13: case 17:
    case 20: case 21: case 22: case 23: case 24: return 3;
    case 4: case 18: return 4;
    default: return -1;
    }
}

static void parse_decl(VsProgram *p, const uint32_t *t, uint32_t n) {
    uint32_t stream = 0, offset = 0;
    for (uint32_t i = 0; i < n; i++) {
        uint32_t tok = t[i];
        if (tok == 0xFFFFFFFFu) break;
        switch (tok >> 29) {
        case 1: stream = tok & 0xF; offset = 0; break;               /* D3DVSD_STREAM */
        case 2:
            if (tok & 0x10000000u) offset += 4 * ((tok >> 16) & 0xF);   /* D3DVSD_SKIP */
            else {
                uint32_t reg = tok & 0x1F, type = (tok >> 16) & 0xF;
                if (reg < 16) { p->in[reg].used = 1; p->in[reg].stream = (uint8_t)stream; p->in[reg].type = (uint8_t)type;
                                p->in[reg].offset = (uint16_t)offset; }
                static const uint8_t sz[8] = { 4, 8, 12, 16, 4, 4, 4, 8 };
                offset += type < 8 ? sz[type] : 4;
            }
            break;
        case 4: {                                                    /* D3DVSD_CONST */
            uint32_t cnt = (tok >> 25) & 0xF, addr = tok & 0x7F;
            for (uint32_t k = 0; k < cnt && i + 4 <= n; k++, addr++) {
                if (addr < 96) { memcpy(p->defs[addr], &t[i + 1], 16); p->has_def[addr] = 1; }
                i += 4;
            }
            break;
        }
        case 5: i += (tok >> 24) & 0x1F; break;                     /* D3DVSD_EXT */
        default: break;
        }
    }
}

/* register file slot of a source register (out of range or unreadable: the zero vector) */
static inline uint16_t src_slot(uint8_t type, int num) {
    switch (type) {
    case RT_TEMP: return num >= 0 && num < 12 ? (uint16_t)(VSF_TEMP + num) : VSF_ZERO;
    case RT_INPUT: return num >= 0 && num < 16 ? (uint16_t)(VSF_IN + num) : VSF_ZERO;
    case RT_CONST: return num >= 0 && num < 96 ? (uint16_t)(VSF_CONST + num) : VSF_ZERO;
    default: return VSF_ZERO;
    }
}

static Src decode_src(uint32_t t) {
    Src s;
    s.type = (uint8_t)((t >> 28) & 7);
    s.num = (uint16_t)(t & 0x1FFF);
    s.rel = (t >> 13) & 1;
    s.neg = ((t >> 24) & 0xF) == 1;
    for (int k = 0; k < 4; k++) s.swz[k] = (uint8_t)((t >> (16 + 2 * k)) & 3);
    s.ident = !s.neg && s.swz[0] == 0 && s.swz[1] == 1 && s.swz[2] == 2 && s.swz[3] == 3;
    s.idx = src_slot(s.type, s.num);
    return s;
}

/* register file slot of a destination (unwritable: the dummy vector) */
static uint16_t dst_slot(uint8_t type, uint16_t num) {
    switch (type) {
    case RT_TEMP: return num < 12 ? (uint16_t)(VSF_TEMP + num) : VSF_DUMMY;
    case RT_RASTOUT: return num == 0 ? VSF_OPOS : VSF_DUMMY;
    case RT_ATTROUT: return num < 2 ? (uint16_t)(VSF_OD + num) : VSF_DUMMY;
    case RT_TEXOUT: return num < 8 ? (uint16_t)(VSF_OT + num) : VSF_DUMMY;
    default: return VSF_DUMMY;
    }
}

static int parse_code(VsProgram *p, const uint32_t *t, uint32_t n) {
    if (!n || (t[0] & 0xFFFF0000u) != 0xFFFE0000u) return 0;
    p->code = calloc(n, sizeof(Insn));
    for (uint32_t i = 1; i < n;) {
        uint32_t tok = t[i++], op = tok & 0xFFFF;
        if (tok == 0x0000FFFFu) break;
        if (op == 0xFFFE) { i += (tok >> 16) & 0x7FFF; continue; }   /* comment */
        if (op == 81) {                                             /* def c#, f, f, f, f */
            uint32_t r = t[i] & 0x1FFF;
            if (r < 96) { memcpy(p->defs[r], &t[i + 1], 16); p->has_def[r] = 1; }
            i += 5;
            continue;
        }
        int np = nparams(op);
        if (np < 0) { fprintf(stderr, "[vs] unsupported opcode %u\n", op); return 0; }
        if (np == 0) continue;
        Insn *in = &p->code[p->n++];
        in->op = (uint16_t)op;
        uint32_t d = t[i++];
        in->dtype = (uint8_t)((d >> 28) & 7);
        in->dnum = (uint16_t)(d & 0x1FFF);
        in->dmask = (uint8_t)((d >> 16) & 0xF);
        in->didx = dst_slot(in->dtype, in->dnum);
        in->nsrc = (uint8_t)(np - 1);
        for (int k = 0; k < np - 1; k++) {
            uint32_t s = t[i++];
            in->s[k] = decode_src(s);
            if (in->s[k].rel && (t[0] & 0xFFFF) >= 0x0200) i++;     /* vs.2.0 relative token (not expected) */
        }
    }
    return 1;
}

static VsProgram *program_for(Shader *sh) {
    if (sh->backend) return sh->backend;
    VsProgram *p = calloc(1, sizeof *p);
    parse_decl(p, sh->decl, sh->decl_len);
    if (!sh->code) { p->fixed_function = 1; p->ok = 1; }
    else p->ok = parse_code(p, sh->code, sh->code_len);
    if (!p->ok) fprintf(stderr, "[vs] could not decode shader (version %08x)\n", sh->code ? sh->code[0] : 0);
    for (int i = 0; i < 16; i++) if (p->in[i].used) p->used_in[p->nused++] = (uint8_t)i;
    for (int i = 0; i < 96; i++) p->any_def |= p->has_def[i];
    if (getenv("RC_DUMP_VS")) {   /* raw tokens, for offline disassembly */
        fprintf(stderr, "[vsdump %p] decl", (void *)sh);
        for (uint32_t i = 0; i < sh->decl_len; i++) fprintf(stderr, " %08x", sh->decl[i]);
        fprintf(stderr, "\n[vsdump %p] code", (void *)sh);
        for (uint32_t i = 0; i < sh->code_len; i++) fprintf(stderr, " %08x", sh->code[i]);
        fprintf(stderr, "\n");
    }
    sh->backend = p;
    return p;
}

int vs_is_fixed_function(Shader *sh) { return program_for(sh)->fixed_function; }

/* ------------------------------------------------------------------ input fetch */

static void fetch(const uint8_t *src, uint32_t type, float *o) {
    o[0] = o[1] = o[2] = 0; o[3] = 1;
    switch (type) {
    case 0: memcpy(o, src, 4); break;
    case 1: memcpy(o, src, 8); break;
    case 2: memcpy(o, src, 12); break;
    case 3: memcpy(o, src, 16); break;
    case 4: o[0] = src[2] / 255.f; o[1] = src[1] / 255.f; o[2] = src[0] / 255.f; o[3] = src[3] / 255.f; break;  /* D3DCOLOR */
    case 5: for (int k = 0; k < 4; k++) o[k] = src[k]; break;                                                 /* UBYTE4 */
    case 6: { int16_t s[2]; memcpy(s, src, 4); o[0] = s[0]; o[1] = s[1]; break; }                           /* SHORT2 */
    case 7: { int16_t s[4]; memcpy(s, src, 8); for (int k = 0; k < 4; k++) o[k] = s[k]; break; }            /* SHORT4 */
    }
}

/* ------------------------------------------------------------------ execution */

/* reads a source operand (row: register offset for the m4x4-family rows) */
static inline void read_src(const Src *s, float (*f)[4], int a0, int row, float *o) {
    int rel = s->rel && s->type == RT_CONST;   /* only constants are addressed through a0 */
    const float *base = f[row || rel ? src_slot(s->type, s->num + row + (rel ? a0 : 0)) : s->idx];
    if (s->ident) { memcpy(o, base, 16); return; }
    if (s->neg) for (int k = 0; k < 4; k++) o[k] = -base[s->swz[k]];
    else for (int k = 0; k < 4; k++) o[k] = base[s->swz[k]];
}

static inline float dp3(const float *a, const float *b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
static inline float dp4(const float *a, const float *b) { return dp3(a, b) + a[3] * b[3]; }

int vs_trace;   /* set by the renderer to trace one vertex */

/* Once per draw: constants (with the shader's own def/declaration values over them), unused inputs. */
void vs_begin(VsCtx *x, Shader *sh, const float consts[96][4], const VsStreams *st) {
    VsProgram *p = program_for(sh);
    x->p = p;
    x->st = st;
    memcpy(x->f[VSF_CONST], consts, sizeof(float) * 96 * 4);
    if (p->any_def) for (int i = 0; i < 96; i++) if (p->has_def[i]) memcpy(x->f[VSF_CONST + i], p->defs[i], 16);
    memset(x->f[VSF_ZERO], 0, 16);
    for (int i = 0; i < 16; i++) { float *v = x->f[VSF_IN + i]; v[0] = v[1] = v[2] = 0; v[3] = 1; }
}

void vs_exec(VsCtx *x, uint32_t index, VsOut *out) {
    VsProgram *p = x->p;
    const VsStreams *st = x->st;
    float (*f)[4] = x->f;
    for (int u = 0; u < p->nused; u++) {
        int i = p->used_in[u];
        const uint8_t *data = st->data[p->in[i].stream];
        if (!data) continue;   /* stays (0, 0, 0, 1) */
        fetch(data + (size_t)index * st->stride[p->in[i].stream] + p->in[i].offset, p->in[i].type, f[VSF_IN + i]);
    }
    const float (*v)[4] = (const float (*)[4])f[VSF_IN];
    if (p->fixed_function) {
        /* declaration-only shader: D3D's fixed register mapping (v0 position, v5/v6 colors, v7+ texcoords) */
        const float *m = st->ff_mvp;
        const float *x = v[0];
        for (int k = 0; k < 4; k++) out->pos[k] = x[0] * m[k] + x[1] * m[4 + k] + x[2] * m[8 + k] + m[12 + k];
        if (p->in[5].used) memcpy(out->d0, v[5], 16); else for (int k = 0; k < 4; k++) out->d0[k] = 1;
        if (p->in[6].used) memcpy(out->d1, v[6], 16); else memset(out->d1, 0, 16);
        for (int t = 0; t < 4; t++) memcpy(out->tex[t], v[7 + t], 16);
        return;
    }
    if (!p->ok) { memset(out, 0, sizeof *out); return; }

    /* constants: declaration/def values override nothing set later by SetVertexShaderConstant, but D3D
       applies declaration constants at SetVertexShader time; using them as a base is close enough */
    memset(f[VSF_TEMP], 0, sizeof(float) * 12 * 4);
    static const float out_init[11][4] = { { 0, 0, 0, 1 }, { 1, 1, 1, 1 }, { 0, 0, 0, 0 }, { 0, 0, 0, 1 }, { 0, 0, 0, 1 },
                                           { 0, 0, 0, 1 }, { 0, 0, 0, 1 }, { 0, 0, 0, 1 }, { 0, 0, 0, 1 }, { 0, 0, 0, 1 },
                                           { 0, 0, 0, 1 } };   /* oPos, oD0, oD1, oT0-oT7 */
    memcpy(f[VSF_OPOS], out_init, sizeof out_init);
    int a0 = 0;

    for (uint32_t i = 0; i < p->n; i++) {
        const Insn *in = &p->code[i];
        float s0[4], s1[4], s2[4], res[4] = { 0, 0, 0, 0 };
        if (in->nsrc > 0) read_src(&in->s[0], f, a0, 0, s0);
        if (in->nsrc > 1) read_src(&in->s[1], f, a0, 0, s1);
        if (in->nsrc > 2) read_src(&in->s[2], f, a0, 0, s2);
        switch (in->op) {
        case 1: memcpy(res, s0, 16); break;                                          /* mov */
        case 2: for (int k = 0; k < 4; k++) res[k] = s0[k] + s1[k]; break;          /* add */
        case 3: for (int k = 0; k < 4; k++) res[k] = s0[k] - s1[k]; break;          /* sub */
        case 4: for (int k = 0; k < 4; k++) res[k] = s0[k] * s1[k] + s2[k]; break;  /* mad */
        case 5: for (int k = 0; k < 4; k++) res[k] = s0[k] * s1[k]; break;          /* mul */
        case 6: { float x = s0[3]; float y = x == 1.f ? 1.f : x == 0.f ? INFINITY : 1.f / x;   /* rcp */
                  for (int k = 0; k < 4; k++) res[k] = y; break; }
        case 7: { float x = fabsf(s0[3]); float y = x == 1.f ? 1.f : x == 0.f ? INFINITY : 1.f / sqrtf(x);  /* rsq */
                  for (int k = 0; k < 4; k++) res[k] = y; break; }
        case 8: { float d = dp3(s0, s1); for (int k = 0; k < 4; k++) res[k] = d; break; }
        case 9: { float d = dp4(s0, s1); for (int k = 0; k < 4; k++) res[k] = d; break; }
        case 10: for (int k = 0; k < 4; k++) res[k] = s0[k] < s1[k] ? s0[k] : s1[k]; break;
        case 11: for (int k = 0; k < 4; k++) res[k] = s0[k] >= s1[k] ? s0[k] : s1[k]; break;
        case 12: for (int k = 0; k < 4; k++) res[k] = s0[k] < s1[k] ? 1.f : 0.f; break;   /* slt */
        case 13: for (int k = 0; k < 4; k++) res[k] = s0[k] >= s1[k] ? 1.f : 0.f; break;  /* sge */
        case 14: { float y = exp2f(s0[3]); for (int k = 0; k < 4; k++) res[k] = y; break; }   /* exp */
        case 15: { float x = fabsf(s0[3]); float y = x == 0.f ? -INFINITY : log2f(x);         /* log */
                   for (int k = 0; k < 4; k++) res[k] = y; break; }
        case 16: {                                                                   /* lit */
            float pw = s0[3] < -128.f ? -128.f : s0[3] > 128.f ? 128.f : s0[3];
            res[0] = 1; res[3] = 1;
            res[1] = s0[0] > 0 ? s0[0] : 0;
            res[2] = s0[0] > 0 && s0[1] > 0 ? powf(s0[1], pw) : 0;
            break;
        }
        case 17: res[0] = 1; res[1] = s0[1] * s1[1]; res[2] = s0[2]; res[3] = s1[3]; break;   /* dst */
        case 18: for (int k = 0; k < 4; k++) res[k] = s0[k] * (s1[k] - s2[k]) + s2[k]; break; /* lrp */
        case 19: for (int k = 0; k < 4; k++) res[k] = s0[k] - floorf(s0[k]); break;           /* frc */
        case 20: case 21: case 22: case 23: case 24: {                               /* m4x4 m4x3 m3x4 m3x3 m3x2 */
            int rows = in->op == 20 || in->op == 22 ? 4 : in->op == 24 ? 2 : 3, four = in->op <= 21;
            res[0] = four ? dp4(s0, s1) : dp3(s0, s1);   /* row 0 is s1 itself */
            for (int k = 1; k < rows; k++) {
                float m[4];
                read_src(&in->s[1], f, a0, k, m);
                res[k] = four ? dp4(s0, m) : dp3(s0, m);
            }
            break;
        }
        case 78: { float w = s0[3]; float f = floorf(w);                             /* expp */
                   res[0] = exp2f(f); res[1] = w - f; res[2] = exp2f(w); res[3] = 1; break; }
        case 79: { float x = fabsf(s0[3]);                                           /* logp */
                   if (x == 0.f) { res[0] = res[2] = -INFINITY; res[1] = 1; }
                   else { int e; float m = frexpf(x, &e); res[0] = (float)(e - 1); res[1] = m * 2; res[2] = log2f(x); }
                   res[3] = 1; break; }
        default: break;
        }
        if (in->dtype == RT_ADDR) { if (in->dmask & 1) a0 = (int)floorf(res[0]); continue; }
        float *dst = f[in->didx];
        if (in->dmask == 0xF) memcpy(dst, res, 16);
        else for (int k = 0; k < 4; k++) if (in->dmask & (1 << k)) dst[k] = res[k];
    }
    if (vs_trace) {
        const float (*r)[4] = (const float (*)[4])f[VSF_TEMP], (*c)[4] = (const float (*)[4])f[VSF_CONST];
        const float *o_pos = f[VSF_OPOS], (*o_d)[4] = (const float (*)[4])f[VSF_OD], (*o_t)[4] = (const float (*)[4])f[VSF_OT];
        fprintf(stderr, "[vstrace %p] idx %u\n", (void *)p, index);
        for (int k = 0; k < 16; k++) if (p->in[k].used)
            fprintf(stderr, "  v%d = %g %g %g %g\n", k, v[k][0], v[k][1], v[k][2], v[k][3]);
        for (int k = 0; k < 12; k++) fprintf(stderr, "  r%d = %g %g %g %g\n", k, r[k][0], r[k][1], r[k][2], r[k][3]);
        fprintf(stderr, "  oPos = %g %g %g %g  oD0 = %g %g %g %g  oD1 = %g %g %g %g  oT0 = %g %g\n", o_pos[0], o_pos[1],
                o_pos[2], o_pos[3], o_d[0][0], o_d[0][1], o_d[0][2], o_d[0][3], o_d[1][0], o_d[1][1], o_d[1][2],
                o_d[1][3], o_t[0][0], o_t[0][1]);
        for (int k = 0; k < 96; k++)
            fprintf(stderr, "  c%d = %g %g %g %g\n", k, c[k][0], c[k][1], c[k][2], c[k][3]);
    }
    memcpy(out->pos, f[VSF_OPOS], 16);
    memcpy(out->d0, f[VSF_OD], 16);
    memcpy(out->d1, f[VSF_OD + 1], 16);
    memcpy(out->tex, f[VSF_OT], 4 * 16);
}

/* one vertex on its own (sets the draw up each time: callers with many vertices use vs_begin/vs_exec) */
void vs_run(Shader *sh, const float consts[96][4], const VsStreams *st, uint32_t index, VsOut *out) {
    static VsCtx x;   /* large: not on the stack */
    vs_begin(&x, sh, consts, st);
    vs_exec(&x, index, out);
}

/* ------------------------------------------------------------------ on the GPU */

int vs_gpu_inputs(Shader *sh) {
    VsProgram *p = program_for(sh);
    return p->ok && !p->fixed_function ? p->nused : -1;
}

uint32_t vs_stream_mask(Shader *sh) {
    VsProgram *p = program_for(sh);
    uint32_t m = 0;
    for (int u = 0; u < p->nused; u++) m |= 1u << p->in[p->used_in[u]].stream;
    return m;
}

void vs_constants(Shader *sh, const float consts[96][4], float out[96][4]) {
    VsProgram *p = program_for(sh);
    memcpy(out, consts, sizeof(float) * 96 * 4);
    if (p->any_def) for (int i = 0; i < 96; i++) if (p->has_def[i]) memcpy(out[i], p->defs[i], 16);
}

/* the input registers the shader reads, as vec4s in used_in order (the GPU vertex layout) */
void vs_fetch(VsCtx *x, uint32_t index, float *o) {
    VsProgram *p = x->p;
    const VsStreams *st = x->st;
    for (int u = 0; u < p->nused; u++, o += 4) {
        int i = p->used_in[u];
        const uint8_t *data = st->data[p->in[i].stream];
        if (!data) { o[0] = o[1] = o[2] = 0; o[3] = 1; continue; }
        fetch(data + (size_t)index * st->stride[p->in[i].stream] + p->in[i].offset, p->in[i].type, o);
    }
}

typedef struct { char *s; size_t n, cap; } Str;
static void put(Str *b, const char *fmt, ...) {
    va_list ap;
    for (;;) {
        va_start(ap, fmt);
        int k = vsnprintf(b->s ? b->s + b->n : NULL, b->s ? b->cap - b->n : 0, fmt, ap);
        va_end(ap);
        if (k >= 0 && b->s && b->n + (size_t)k < b->cap) { b->n += (size_t)k; return; }
        b->cap = (b->cap + (size_t)(k > 0 ? k : 0)) * 2 + 4096;
        b->s = realloc(b->s, b->cap);
    }
}

/* a source operand as a WGSL vec4 expression (row: register offset for the m4x4-family rows) */
static void src_expr(Str *b, const Src *s, int row) {
    char base[48];
    int num = s->num + row;
    switch (s->type) {
    case RT_TEMP: if (num < 12) snprintf(base, sizeof base, "r[%d]", num); else strcpy(base, "vec4<f32>(0.0)"); break;
    case RT_INPUT: if (num < 16) snprintf(base, sizeof base, "v%d", num); else strcpy(base, "vec4<f32>(0.0)"); break;
    case RT_CONST:
        if (s->rel) snprintf(base, sizeof base, "cst(%d + a0)", num);
        else if (num < 96) snprintf(base, sizeof base, "vc.c[%d]", num);
        else strcpy(base, "vec4<f32>(0.0)");
        break;
    default: strcpy(base, "vec4<f32>(0.0)"); break;
    }
    static const char xyzw[] = "xyzw";
    char sw[6] = ".";
    if (s->swz[0] == 0 && s->swz[1] == 1 && s->swz[2] == 2 && s->swz[3] == 3) sw[0] = 0;
    else for (int k = 0; k < 4; k++) sw[1 + k] = xyzw[s->swz[k]];
    put(b, s->neg ? "(-(%s%s))" : "(%s%s)", base, sw);
}

/* WGSL for the vertex stage: entry point "vsp", inputs @location(0..nused-1) = the used v registers in
   order, constants in @group(2) (vc.c[96]). Outputs match the fixed-function vertex stage's VO, with the
   same half-pixel offset and color saturation as the CPU path. NULL if the shader can't run on the GPU. */
char *vs_to_wgsl(Shader *sh) {
    VsProgram *p = program_for(sh);
    if (!p->ok || p->fixed_function) return NULL;
    Str b = { 0 };
    put(&b, "struct VC { c: array<vec4<f32>, 96> };\n@group(2) @binding(0) var<uniform> vc: VC;\n"
            "fn cst(i: i32) -> vec4<f32> { if (i < 0 || i >= 96) { return vec4<f32>(0.0); } return vc.c[i]; }\n"
            "fn rcp1(x: f32) -> f32 { if (x == 1.0) { return 1.0; } return 1.0 / x; }\n"
            "fn rsq1(x: f32) -> f32 { let a = abs(x); if (a == 1.0) { return 1.0; } return inverseSqrt(a); }\n"
            "struct VSI {");
    for (int u = 0; u < p->nused; u++) put(&b, "%s @location(%d) v%d: vec4<f32>", u ? "," : "", u, p->used_in[u]);
    if (!p->nused) put(&b, " @builtin(vertex_index) vid: u32");
    put(&b, " };\n@vertex fn vsp(vi: VSI) -> VO {\n");
    for (int i = 0; i < 16; i++) {
        if (p->in[i].used) put(&b, "  let v%d = vi.v%d;\n", i, i);
        else put(&b, "  let v%d = vec4<f32>(0.0, 0.0, 0.0, 1.0);\n", i);
    }
    put(&b, "  var r: array<vec4<f32>, 12>;\n  var a0: i32 = 0;\n  var dummy: vec4<f32>;\n"
            "  var opos = vec4<f32>(0.0, 0.0, 0.0, 1.0);\n  var od: array<vec4<f32>, 2>;\n  od[0] = vec4<f32>(1.0);\n"
            "  var ot: array<vec4<f32>, 8>;\n  for (var k = 0; k < 8; k++) { ot[k] = vec4<f32>(0.0, 0.0, 0.0, 1.0); }\n");
    for (uint32_t i = 0; i < p->n; i++) {
        const Insn *in = &p->code[i];
        put(&b, "  {\n");
        for (int k = 0; k < in->nsrc; k++) { put(&b, "    let s%d = ", k); src_expr(&b, &in->s[k], 0); put(&b, ";\n"); }
        put(&b, "    var res = vec4<f32>(0.0);\n");
        switch (in->op) {
        case 1: put(&b, "    res = s0;\n"); break;
        case 2: put(&b, "    res = s0 + s1;\n"); break;
        case 3: put(&b, "    res = s0 - s1;\n"); break;
        case 4: put(&b, "    res = s0 * s1 + s2;\n"); break;
        case 5: put(&b, "    res = s0 * s1;\n"); break;
        case 6: put(&b, "    res = vec4<f32>(rcp1(s0.w));\n"); break;
        case 7: put(&b, "    res = vec4<f32>(rsq1(s0.w));\n"); break;
        case 8: put(&b, "    res = vec4<f32>(dot(s0.xyz, s1.xyz));\n"); break;
        case 9: put(&b, "    res = vec4<f32>(dot(s0, s1));\n"); break;
        case 10: put(&b, "    res = select(s1, s0, s0 < s1);\n"); break;
        case 11: put(&b, "    res = select(s1, s0, s0 >= s1);\n"); break;
        case 12: put(&b, "    res = select(vec4<f32>(0.0), vec4<f32>(1.0), s0 < s1);\n"); break;
        case 13: put(&b, "    res = select(vec4<f32>(0.0), vec4<f32>(1.0), s0 >= s1);\n"); break;
        case 14: put(&b, "    res = vec4<f32>(exp2(s0.w));\n"); break;
        case 15: put(&b, "    res = vec4<f32>(log2(abs(s0.w)));\n"); break;
        case 16: put(&b, "    let pw = clamp(s0.w, -128.0, 128.0);\n"
                         "    res = vec4<f32>(1.0, max(s0.x, 0.0), select(0.0, pow(s0.y, pw), s0.x > 0.0 && s0.y > 0.0), 1.0);\n");
                 break;
        case 17: put(&b, "    res = vec4<f32>(1.0, s0.y * s1.y, s0.z, s1.w);\n"); break;
        case 18: put(&b, "    res = s0 * (s1 - s2) + s2;\n"); break;
        case 19: put(&b, "    res = s0 - floor(s0);\n"); break;
        case 20: case 21: case 22: case 23: case 24: {
            int rows = in->op == 20 || in->op == 22 ? 4 : in->op == 24 ? 2 : 3, four = in->op <= 21;
            for (int k = 0; k < rows; k++) {
                put(&b, "    res.%c = dot(s0%s, ", "xyzw"[k], four ? "" : ".xyz");
                src_expr(&b, &in->s[1], k);
                put(&b, "%s);\n", four ? "" : ".xyz");
            }
            break;
        }
        case 78: put(&b, "    let w = s0.w; let fw = floor(w);\n    res = vec4<f32>(exp2(fw), w - fw, exp2(w), 1.0);\n"); break;
        case 79: put(&b, "    let x = abs(s0.w); let e = floor(log2(x));\n"
                         "    res = vec4<f32>(e, x / exp2(e), log2(x), 1.0);\n"); break;
        default: break;
        }
        if (in->dtype == RT_ADDR) {
            if (in->dmask & 1) put(&b, "    a0 = i32(floor(res.x));\n");
        } else {
            char dst[24];
            switch (in->didx >= VSF_OT && in->didx < VSF_OT + 8 ? -1 : (int)in->didx) {
            case -1: snprintf(dst, sizeof dst, "ot[%d]", in->didx - VSF_OT); break;
            case VSF_OPOS: strcpy(dst, "opos"); break;
            case VSF_OD: strcpy(dst, "od[0]"); break;
            case VSF_OD + 1: strcpy(dst, "od[1]"); break;
            case VSF_DUMMY: strcpy(dst, "dummy"); break;
            default: snprintf(dst, sizeof dst, "r[%d]", in->didx - VSF_TEMP); break;
            }
            if (in->dmask == 0xF) put(&b, "    %s = res;\n", dst);
            else for (int k = 0; k < 4; k++) if (in->dmask & (1 << k)) put(&b, "    %s.%c = res.%c;\n", dst, "xyzw"[k], "xyzw"[k]);
        }
        put(&b, "  }\n");
    }
    put(&b, "  var o: VO;\n  var q = opos;\n  q.x = q.x + q.w / u.vp.z;\n  q.y = q.y - q.w / u.vp.w;\n  o.pos = q;\n"
            "  o.diffuse = saturate(od[0]);\n  o.specular = saturate(od[1]);\n"
            "  o.tc0 = ot[0]; o.tc1 = ot[1]; o.tc2 = ot[2]; o.tc3 = ot[3];\n  return o;\n}\n");
    return b.s;
}
