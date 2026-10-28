package app.hermes.companion.backend

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import app.companion.backend.AgentRuntime
import app.hermes.companion.design.CompanionColor

/** Runtime identity uses the same angular, single-stroke icon language as Companion chrome. */
@androidx.compose.runtime.Composable
fun RuntimeMark(runtime: AgentRuntime,modifier: Modifier=Modifier) {
    val color=if(runtime==AgentRuntime.CODYNC) CompanionColor.Accent else CompanionColor.Signal
    Canvas(modifier) {
        val u=size.minDimension/24
        fun line(a:Float,b:Float,c:Float,d:Float)=drawLine(color,Offset(a*u,b*u),Offset(c*u,d*u),1.8f*u,StrokeCap.Round)
        when(runtime) {
            AgentRuntime.HERMES -> { line(4f,5f,4f,19f);line(20f,5f,20f,19f);line(4f,12f,20f,12f) }
            AgentRuntime.OPENCLAW -> { line(5f,4f,3f,11f);line(3f,11f,12f,20f);line(12f,20f,21f,11f);line(21f,11f,19f,4f);line(9f,6f,12f,14f);line(12f,14f,15f,6f) }
            AgentRuntime.CODYNC -> { line(8f,5f,2f,12f);line(2f,12f,8f,19f);line(16f,5f,22f,12f);line(22f,12f,16f,19f);line(14f,4f,10f,20f) }
        }
    }
}
