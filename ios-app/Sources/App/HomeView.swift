import SwiftUI
import NetworkExtension

struct HomeView: View {
    @EnvironmentObject private var vpn: VPNController

    private let teal = Color(red: 0.27, green: 0.91, blue: 0.82)
    private let violet = Color(red: 0.48, green: 0.39, blue: 0.96)

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.025, green: 0.045, blue: 0.10), Color(red: 0.07, green: 0.055, blue: 0.16)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            VStack(spacing: 26) {
                header
                Spacer(minLength: 8)
                networkOrb
                Text(vpn.statusLabel)
                    .font(.system(size: 25, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
                Text(vpn.message)
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.66))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
                controls
                metrics
                if let ip = vpn.egressIP, vpn.egressVerified {
                    HStack(spacing: 8) {
                        Image(systemName: "checkmark.shield.fill").foregroundStyle(teal)
                        Text("IP خروجی تأییدشده:")
                            .foregroundStyle(.white.opacity(0.62))
                        Text(ip)
                            .font(.system(.footnote, design: .monospaced).weight(.semibold))
                            .foregroundStyle(teal)
                            .textSelection(.enabled)
                    }
                    .font(.footnote)
                    .padding(.top, 2)
                }
                Spacer(minLength: 8)
                Text("MOLIDO MESH AI  •  iOS PREVIEW")
                    .font(.system(size: 10, weight: .medium, design: .rounded))
                    .tracking(2)
                    .foregroundStyle(.white.opacity(0.35))
            }
            .padding(.horizontal, 22)
            .padding(.top, 18)
            .padding(.bottom, 20)
        }
        .preferredColorScheme(.dark)
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text("MOLIDO")
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .tracking(3)
                    .foregroundStyle(teal)
                Text("Mesh AI")
                    .font(.system(size: 27, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
            }
            Spacer()
            Image(systemName: "shield.lefthalf.filled")
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(teal)
                .padding(13)
                .background(.white.opacity(0.07), in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.10), lineWidth: 1))
        }
    }

    private var networkOrb: some View {
        ZStack {
            Circle().fill(teal.opacity(0.06)).frame(width: 238, height: 238).blur(radius: 18)
            Circle().stroke(teal.opacity(0.16), lineWidth: 1).frame(width: 220, height: 220)
            Circle().stroke(violet.opacity(0.24), style: StrokeStyle(lineWidth: 1, dash: [3, 7])).frame(width: 184, height: 184)
            Circle().fill(LinearGradient(colors: [teal.opacity(0.22), violet.opacity(0.25)], startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: 148, height: 148)
                .overlay(Circle().stroke(.white.opacity(0.22), lineWidth: 1))
                .shadow(color: teal.opacity(0.22), radius: 28)
            Image(systemName: "point.3.connected.trianglepath.dotted")
                .font(.system(size: 52, weight: .light))
                .foregroundStyle(teal)
        }
        .frame(height: 250)
        .accessibilityLabel("Molido network visualization")
    }

    private var controls: some View {
        VStack(spacing: 10) {
            if !vpn.profileInstalled {
                Button(action: vpn.prepareProfile) {
                    Label("آماده‌سازی پروفایل VPN", systemImage: "slider.horizontal.3")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle(color: violet))
            } else if vpn.status == .connected || vpn.status == .connecting || vpn.status == .reasserting {
                Button(action: vpn.disconnect) {
                    Label("قطع اتصال", systemImage: "stop.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle(color: Color(red: 0.88, green: 0.30, blue: 0.40)))
            } else {
                Button(action: vpn.connect) {
                    Label("اتصال هوشمند", systemImage: "bolt.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle(color: teal))
            }
        }
    }

    private var metrics: some View {
        HStack(spacing: 10) {
            metricCard(title: "پینگ", value: "—", unit: "ms")
            metricCard(title: "دانلود", value: "—", unit: "Mbps")
            metricCard(title: "آپلود", value: "—", unit: "Mbps")
        }
    }

    private func metricCard(title: String, value: String, unit: String) -> some View {
        VStack(spacing: 7) {
            Text(title).font(.caption).foregroundStyle(.white.opacity(0.55))
            Text(value).font(.system(size: 21, weight: .semibold, design: .rounded)).foregroundStyle(.white)
            Text(unit).font(.system(size: 10)).foregroundStyle(teal.opacity(0.8))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 15)
        .background(.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(.white.opacity(0.08), lineWidth: 1))
    }
}

private struct PrimaryButtonStyle: ButtonStyle {
    let color: Color
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 16, weight: .semibold, design: .rounded))
            .foregroundStyle(Color.black.opacity(0.88))
            .padding(.vertical, 17)
            .background(color.opacity(configuration.isPressed ? 0.72 : 0.96), in: RoundedRectangle(cornerRadius: 17))
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
    }
}
