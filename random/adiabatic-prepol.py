import numpy as np
from scipy.integrate import solve_ivp
import matplotlib.pyplot as plt

# --- Physical constants ---
gamma = 2.675e8  # rad/s/T
k_B = 1.381e-23  # J/K
hbar = 1.055e-34  # J·s
T_body = 310      # K

# --- Circuit parameters ---
B0 = 10e-3       # T
k = 10e-3 / 100  # T/A coil constant
I0 = 1000        # A
wire_length_m = 40 * np.pi * 0.30
wire_length_ft = wire_length_m / 0.3048
R = 25 * wire_length_ft / 100
C = 10e-3
tau = R * C

# --- Relaxation (soft tissue at low field) ---
T1 = 0.3   # s (shorter at low field)
T2 = 0.05  # s

print("="*60)
print("PREPOLARIZATION PULSE SIMULATION")
print("="*60)
print(f"B0 = {B0*1e3:.1f} mT")
print(f"Peak pulse field = {I0*k*1e3:.1f} mT")
print(f"Peak total field = {(B0 + I0*k)*1e3:.1f} mT")
print(f"Field ratio B_peak/B0 = {(B0 + I0*k)/B0:.1f}x")
print(f"RC time constant = {tau*1e6:.1f} µs")
print(f"T1 = {T1*1e3:.0f} ms")
print(f"tau/T1 = {tau/T1:.2e}  <--- this is the problem!")
print()

# --- Time-dependent field and equilibrium magnetization ---
# B(t) = B0 + I0*k*exp(-t/tau)  ALL ALONG Z
# M0(t) = M0_base * B(t)/B0  (Curie law: equilibrium M scales with B)

def B_total(t):
    return B0 + I0 * k * np.exp(-t / tau)

def M0_eq(t):
    """Equilibrium magnetization, normalized so M0(B0) = 1"""
    return B_total(t) / B0

# --- Full vector Bloch equation ---
# dM/dt = gamma * (M x B) - (Mx xhat + My yhat)/T2 - (Mz - M0(t))/T1 * zhat
# With B = [0, 0, Bz(t)]:
#   M x B = [My*Bz, -Mx*Bz, 0]

def bloch_full(t, M):
    Mx, My, Mz = M
    Bz = B_total(t)
    M0t = M0_eq(t)

    dMx = gamma * My * Bz - Mx / T2
    dMy = -gamma * Mx * Bz - My / T2
    dMz = -(Mz - M0t) / T1
    return [dMx, dMy, dMz]

# --- Simulation ---
# Simulate long enough to see T1 dynamics (several T1)
t_end = 3 * T1
M_init = [0.0, 0.0, 1.0]  # equilibrium at B0, normalized

sol = solve_ivp(bloch_full, [0, t_end], M_init,
                method='RK45', max_step=tau/10,
                dense_output=True,
                rtol=1e-10, atol=1e-12)

# Dense output - two timescales to plot
t_fine = np.linspace(0, 500*tau, 100000)  # zoom into pulse period
t_long = np.linspace(0, t_end, 100000)     # full T1 timescale

M_fine = sol.sol(t_fine)
M_long = sol.sol(t_long)

# --- Analytic comparison ---
# Since Mx=My=0 always, and B is along z, the torque term vanishes.
# We just have: dMz/dt = -(Mz - M0(t))/T1
# With M0(t) = 1 + (I0*k/B0)*exp(-t/tau) = 1 + A*exp(-t/tau)
# where A = I0*k/B0

A_ratio = I0 * k / B0
print(f"A = I0*k/B0 = {A_ratio:.1f}")
print(f"Peak equilibrium magnetization = {1 + A_ratio:.1f} x M0(B0)")

# Analytic solution for dMz/dt = -(Mz - 1 - A*exp(-t/tau))/T1
# Mz(0) = 1
# Solution: Mz(t) = 1 + A*tau/(tau - T1) * [exp(-t/tau) - exp(-t/T1)]
# (valid when tau != T1)
# Net enhancement after pulse dies: Mz tracks back toward 1 with T1

if abs(tau - T1) > 1e-15:
    def Mz_analytic(t):
        return 1 + A_ratio * tau / (tau - T1) * (np.exp(-t/tau) - np.exp(-t/T1))
else:
    def Mz_analytic(t):
        return 1 + A_ratio * (t/T1) * np.exp(-t/T1)

# Peak Mz enhancement
t_peak_analytic = tau * T1 / (T1 - tau) * np.log(T1/tau) if T1 != tau else tau
Mz_peak = Mz_analytic(t_peak_analytic)
print(f"\nPeak Mz = {Mz_peak:.6f} at t = {t_peak_analytic*1e3:.3f} ms")
print(f"Enhancement over B0 equilibrium: {(Mz_peak - 1)*100:.4f}%")
print(f"\nFor comparison, if pulse lasted >> T1:")
print(f"  Mz would reach {1 + A_ratio:.1f} ({(A_ratio)*100:.0f}% enhancement)")

# --- PLOTTING ---
fig, axes = plt.subplots(4, 1, figsize=(14, 14))

# Panel 1: B field (short timescale)
axes[0].plot(t_fine*1e6, B_total(t_fine)*1e3, 'k-', linewidth=2)
axes[0].axhline(B0*1e3, color='gray', linestyle='--', alpha=0.5, label=f'B₀ = {B0*1e3:.0f} mT')
axes[0].set_ylabel('B_total (mT)')
axes[0].set_xlabel('Time (µs)')
axes[0].set_title(f'Prepolarization Pulse: B(t) = B₀ + I₀·k·exp(-t/τ)\n'
                   f'B₀={B0*1e3:.0f} mT, I₀={I0} A, peak B={B_total(0)*1e3:.0f} mT, τ={tau*1e6:.1f} µs')
axes[0].legend()
axes[0].grid(True, alpha=0.3)

# Panel 2: Equilibrium M0(t) and actual Mz (short timescale)
axes[1].plot(t_fine*1e6, M0_eq(t_fine), 'r--', linewidth=2, label='M₀(t) = B(t)/B₀ (equilibrium target)')
axes[1].plot(t_fine*1e6, M_fine[2], 'b-', linewidth=2, label='Mz(t) (actual)')
axes[1].plot(t_fine*1e6, Mz_analytic(t_fine), 'g--', linewidth=1, alpha=0.7, label='Mz analytic')
axes[1].set_ylabel('M / M₀(B₀)')
axes[1].set_xlabel('Time (µs)')
axes[1].set_title('Short timescale: Mz cannot follow M₀(t) — pulse is too fast vs T₁')
axes[1].legend()
axes[1].grid(True, alpha=0.3)

# Panel 3: Mz on T1 timescale
axes[2].plot(t_long*1e3, M_long[2], 'b-', linewidth=2, label='Mz(t)')
axes[2].plot(t_long*1e3, Mz_analytic(t_long), 'g--', linewidth=1, alpha=0.7, label='Analytic')
axes[2].plot(t_long*1e3, M0_eq(t_long), 'r--', linewidth=1, alpha=0.5, label='M₀(t)')
axes[2].axhline(1.0, color='gray', linestyle=':', alpha=0.5)
axes[2].set_ylabel('M / M₀(B₀)')
axes[2].set_xlabel('Time (ms)')
axes[2].set_title(f'Long timescale (T₁ = {T1*1e3:.0f} ms): Mz barely budged — '
                   f'peak enhancement only {(Mz_peak-1)*100:.3f}%')
axes[2].legend()
axes[2].grid(True, alpha=0.3)

# Panel 4: Mx, My (should be zero)
axes[3].plot(t_long*1e3, M_long[0], 'g-', linewidth=1, label="Mx")
axes[3].plot(t_long*1e3, M_long[1], 'm-', linewidth=1, label="My")
axes[3].set_ylabel('M / M₀(B₀)')
axes[3].set_xlabel('Time (ms)')
axes[3].set_title('Transverse components (should be zero — no torque when M ∥ B)')
axes[3].legend()
axes[3].grid(True, alpha=0.3)
axes[3].ticklabel_format(style='scientific', axis='y', scilimits=(0,0))

plt.tight_layout()
plt.show()

# --- What WOULD work ---
print("\n" + "="*60)
print("WHAT YOU NEED FOR EFFECTIVE PREPOLARIZATION")
print("="*60)
print(f"\nCurrent pulse duration: ~5τ = {5*tau*1e6:.0f} µs")
print(f"T1 of tissue: {T1*1e3:.0f} ms")
print(f"Ratio: {5*tau/T1:.1e}")
print(f"\nFor 95% of max enhancement, need pulse ≈ 3×T1 = {3*T1*1e3:.0f} ms")
print(f"Required C for τ=T1: C = T1/R = {T1/R*1e3:.1f} mF (millifarads!)")
print(f"Energy in cap: ½CV² at say 100V = {0.5 * T1/R * 100**2:.1f} J")
print(f"\nOr: hold DC current of I0 for ~1 second using a power supply")
print(f"Power dissipated: I²R = {I0**2 * R / 1e3:.0f} kW  (!!)")
print(f"\nMore realistic: lower current, longer pulse")
for I_test in [10, 50, 100]:
    B_pol = I_test * k
    enhancement = (B0 + B_pol) / B0
    power = I_test**2 * R
    print(f"  I={I_test:4d} A: B_pol={B_pol*1e3:5.1f} mT, "
          f"enhancement={enhancement:.1f}x, P={power:.0f} W")