package marumasa.panorama_window.adapter;

/**
 * ビルボード (名札など、カメラの方を向く板) の向きの計算。どのバージョンでも共通。
 *
 * <p>立方体の面を描くとき、バニラはビルボードを「その面のカメラ」の方へ向ける。面ごとに向きが違うので、
 * 面の境目にかかったビルボードは 2 つの面で 90 度違う向きに描かれて裂け、面の中央から外れたものは歪む。
 * そこで取り込み中だけ、次のように向きを揃える (普段の画面には何もしない)。
 * <ul>
 *   <li>ビルボード全般: 一巡の始まりのプレイヤーの向き (水平) に揃える。上下はウィンドウのシェーダーが回す</li>
 *   <li>名札: プレイヤーの位置から名札への水平の向きに揃える。6 面すべてで同じなので境目で裂けず、見ている名札は正面を向く</li>
 * </ul>
 */
public final class Billboards {

    private Billboards() {
    }

    /** カメラから (dx, dz) の方向を見るときの yaw (度、Minecraft の向き: 0 = +Z、90 = -X) */
    public static float yawTowards(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** (dx, dz) がほぼ 0 (真上・真下) なら向きを決められない */
    public static boolean hasDirection(double dx, double dz) {
        return dx * dx + dz * dz > 1.0e-6;
    }
}
