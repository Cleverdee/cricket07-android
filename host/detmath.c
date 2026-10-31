/* Android: math the game's x87 code reaches, built into the app. Lockstep needs every peer to compute exactly
 * the same bits, and each phone's own libm depends on its Android version. Defining these here makes the
 * recompiled code (fsin, fcos, fptan, fpatan, fyl2x, f2xm1) use them on every phone. Only + - * / and exact
 * functions (frexp, ldexp, fmod) are used; polynomial coefficients are fdlibm's. */
#ifdef __ANDROID__
#include <math.h>

static const double PIO2_1 = 1.57079632673412561417e+00,
                    PIO2_2 = 6.07710050630396597660e-11, PIO2_2T = 2.02226624879595063154e-21,
                    INVPIO2 = 6.36619772367581382433e-01, TWOPI = 6.28318530717958647693e+00;

static double k_sin(double x) {
    double z = x * x;
    return x + x * z * (-1.66666666666666324348e-01 + z * (8.33333333332248946124e-03 + z * (-1.98412698298579493134e-04 +
           z * (2.75573137070700676789e-06 + z * (-2.50507602534068634195e-08 + z * 1.58969099521155010221e-10)))));
}
static double k_cos(double x) {
    double z = x * x;
    return 1.0 - 0.5 * z + z * z * (4.16666666666666019037e-02 + z * (-1.38888888888741095749e-03 + z * (2.48015872894767294178e-05 +
           z * (-2.75573143513906633035e-07 + z * (2.08757232129817482790e-09 + z * -1.13596475577881948265e-11)))));
}
/* x reduced to [-pi/4, pi/4]; returns the quadrant */
static int reduce(double x, double *r) {
    if (fabs(x) > 1e6) x = fmod(x, TWOPI);
    double n = x * INVPIO2;
    long k = (long)(n + (n < 0 ? -0.5 : 0.5));
    double f = (double)k;
    *r = x - f * PIO2_1 - f * PIO2_2 - f * PIO2_2T;
    return (int)(k & 3);
}
double sin(double x) {
    if (x != x || x - x != 0) return x - x;
    double r;
    switch (reduce(x, &r)) {
    case 0: return k_sin(r);
    case 1: return k_cos(r);
    case 2: return -k_sin(r);
    default: return -k_cos(r);
    }
}
double cos(double x) {
    if (x != x || x - x != 0) return x - x;
    double r;
    switch (reduce(x, &r)) {
    case 0: return k_cos(r);
    case 1: return -k_sin(r);
    case 2: return -k_cos(r);
    default: return k_sin(r);
    }
}
double tan(double x) {
    if (x != x || x - x != 0) return x - x;
    double r;
    int q = reduce(x, &r);
    double s = k_sin(r), c = k_cos(r);
    return q & 1 ? -c / s : s / c;
}

static double atan_pos(double x) {   /* x >= 0 */
    static const double hi[4] = { 4.63647609000806093515e-01, 7.85398163397448278999e-01, 9.82793723247329054082e-01, 1.57079632679489655800e+00 },
                        lo[4] = { 2.26987774529616870924e-17, 3.06161699786838301793e-17, 1.39033110312309984516e-17, 6.12323399573676603587e-17 };
    int id = -1;
    if (x >= 0.4375) {
        if (x < 0.6875) { id = 0; x = (2.0 * x - 1.0) / (2.0 + x); }
        else if (x < 1.1875) { id = 1; x = (x - 1.0) / (x + 1.0); }
        else if (x < 2.4375) { id = 2; x = (x - 1.5) / (1.0 + 1.5 * x); }
        else { id = 3; x = -1.0 / x; }
    }
    double z = x * x, w = z * z;
    double s1 = z * (3.33333333333329318027e-01 + w * (1.42857142725034663711e-01 + w * (9.09088713343650656196e-02 +
                w * (6.66107313738753120669e-02 + w * (4.97687799461593236017e-02 + w * 1.62858201153657823623e-02)))));
    double s2 = w * (-1.99999999998764832476e-01 + w * (-1.11111104054623557880e-01 + w * (-7.69187620504482999495e-02 +
                w * (-5.83357013379057348645e-02 + w * -3.65315727442169155270e-02))));
    if (id < 0) return x - x * (s1 + s2);
    return hi[id] - ((x * (s1 + s2) - lo[id]) - x);
}
double atan2(double y, double x) {
    static const double PI = 3.14159265358979311600e+00, PI_LO = 1.22464679914735317720e-16, PIO2 = 1.57079632679489655800e+00;
    if (x != x || y != y) return x + y;
    if (y == 0) return signbit(x) ? copysign(PI, y) : y;
    if (x == 0) return copysign(PIO2, y);
    if (isinf(y)) return copysign(isinf(x) ? (x > 0 ? PI / 4 : 3 * PI / 4) : PIO2, y);
    if (isinf(x)) return x > 0 ? copysign(0.0, y) : copysign(PI, y);
    double a = atan_pos(fabs(y / x));
    return copysign(x > 0 ? a : PI - (a - PI_LO), y);
}

double log2(double x) {
    if (x != x || x == INFINITY) return x + x;
    if (x == 0) return -INFINITY;
    if (x < 0) return NAN;
    int e;
    double m = frexp(x, &e);              /* 0.5 <= m < 1 */
    if (m < 0.70710678118654752440) { m *= 2; e--; }
    double f = m - 1.0, s = f / (2.0 + f), z = s * s;
    double r = z * (6.666666666666735130e-01 + z * (3.999999999940941908e-01 + z * (2.857142874366239149e-01 +
               z * (2.222219843214978396e-01 + z * (1.818357216161805012e-01 + z * (1.531383769920937332e-01 + z * 1.479819860511658591e-01))))));
    return (double)e + (f - s * (f - r)) * 1.44269504088896340736e+00;
}
double exp2(double x) {
    if (x != x) return x;
    if (x > 1100) return INFINITY;
    if (x < -1200) return 0;
    double n = (double)(long)(x + (x < 0 ? -0.5 : 0.5));
    double t = (x - n) * 6.93147180559945286227e-01, sum = 1.0;
    for (int i = 14; i > 0; i--) sum = 1.0 + sum * t / i;
    return ldexp(sum, (int)n);
}
#endif
