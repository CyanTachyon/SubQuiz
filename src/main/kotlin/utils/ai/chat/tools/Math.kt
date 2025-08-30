package moe.tachyon.quiz.utils.ai.chat.tools

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.aiNegotiationJson

object Math
{
    @Serializable
    private data class ShowParm(
        @JsonSchema.Description("显示的区域范围")
        val bounds: Bounds,
        @JsonSchema.Description("数学公式表达式列表，详见math_demo工具的说明")
        val expressions: List<Expression>,
    )
    {
        @Serializable
        data class Bounds(
            val left: Double,
            val top: Double,
            val right: Double,
            val bottom: Double,
        )

        @OptIn(ExperimentalSerializationApi::class)
        @Serializable
        data class Expression(
            @JsonSchema.Description("数学公式，注意需要使用latex格式，例如: 椭圆\\frac{x^{2}}{4}+\\frac{y^{2}}{9}=1")
            @EncodeDefault(EncodeDefault.Mode.NEVER) val latex: String? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val color: String? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val lineStyle: String? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val lineWidth: Double? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val lineOpacity: Double? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val pointStyle: String? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val pointSize: Double? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val pointOpacity: Double? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val fillOpacity: Double? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val points: Boolean? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val lines: Boolean? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val fill: Boolean? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val hidden: Boolean? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val secret: Boolean? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val sliderBounds: SliderBounds? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val playing: Boolean? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val parametricDomain: Domain? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val polarDomain: Domain? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val id: String? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val dragMode: String? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val label: String? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val showLabel: Boolean? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val labelSize: String? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val labelOrientation: String? = null,
        )
        @Serializable
        data class SliderBounds(
            val min: String,
            val max: String,
            val step: String,
        )
        @Serializable
        data class Domain(
            val min: String,
            val max: String,
        )
    }

    init
    {
        AiTools.registerTool<AiTools.EmptyToolParm>(
            name = "math_demo",
            displayName = "学习绘制函数图像",
            description = "该工具用于获取`math`工具的输入格式说明和示例。",
        )
        {
            AiToolInfo.ToolResult(
                Content("""
                关于`math`工具的输入格式说明：
                ```json
                {
                    "bounds": { // 可视范围
                        "left": -10, // 最左边界
                        "top": 10, // 最上边界
                        "right": 10, // 最右边界
                        "bottom": -10 // 最下边界
                    },
                    "expressions": [
                        {
                            "latex" String, optional, 注意需要使用latex格式，例如想绘制sin(x)函数，则需要写成"\sin(x)" 而不是"sin(x)"。详见：Desmos Expressions
                            "color" String, hex color, optional. See Colors. Default will cycle through 6 default colors.
                            "lineStyle" Enum value, optional. Sets the line drawing style of curves or point lists. See Styles.
                            "lineWidth" Number or String, optional. Determines width of lines in pixels. May be any positive number, or a LaTeX string that evaluates to a positive number. Defaults to 2.5.
                            "lineOpacity" Number or String, optional. Determines opacity of lines. May be a number between 0 and 1, or a LaTeX string that evaluates to a number between 0 and 1. Defaults to 0.9.
                            "pointStyle" Enum value, optional. Sets the point drawing style of point lists. See Styles.
                            "pointSize" Number or String, optional. Determines diameter of points in pixels. May be any positive number, or a LaTeX string that evaluates to a positive number. Defaults to 9.
                            "pointOpacity" Number or String, optional. Determines opacity of points. May be a number between 0 and 1, or a LaTeX string that evaluates to a number between 0 and 1. Defaults to 0.9.
                            "fillOpacity" Number or String, optional. Determines opacity of the interior of a polygon or parametric curve. May be a number between 0 and 1, or a LaTeX string that evaluates to a number between 0 and 1. Defaults to 0.4.
                            "points" Boolean, optional. Determines whether points are plotted for point lists.
                            "lines" Boolean, optional. Determines whether line segments are plotted for point lists.
                            "fill" Boolean, optional. Determines whether a polygon or parametric curve has its interior shaded.
                            "hidden" Boolean, optional. Determines whether the graph is drawn. Defaults to false.
                            "secret" Boolean, optional. Determines whether the expression should appear in the expressions list. Does not affect graph visibility. Defaults to false.
                            "sliderBounds" { min: String, max: String, step: String }, optional. Sets bounds of slider expressions. If step is omitted, '', or undefined, the slider will be continuously adjustable. See note below.
                            "playing"	Boolean, optional. Determines whether the expression should animate, if it is a slider. Defaults to false.
                            "parametricDomain" { min: String, max: String }, optional. Sets bounds of parametric curves. See note below.
                            "polarDomain"	{ min: String, max: String }, optional. Sets bounds of polar curves. See note below.
                            "id" String, optional. Should be a valid property name for a javascript object (letters, numbers, and _).
                            "dragMode" Enum value, optional. Sets the drag mode of a point. See Drag Modes. Defaults to DragModes.AUTO.
                            "label" String, optional. Sets the text label of a point. If a label is set to the empty string then the point's default label (its coordinates) will be applied.
                            "showLabel" Boolean, optional. Sets the visibility of a point's text label.
                            "labelSize" String, optional. Specifies the text size of a point's label as a LaTeX string, which, when computed, multiplies the standard label font size of 110% of the system font size. Defaults to '1'.
                            "labelOrientation" Enum value, optional. Sets the desired position of a point's text label. See LabelOrientations.
                        }
                    ]
                }
                ```
                
                Desmos Expressions
                Expressions are the central mathematical objects used in Desmos. They can plot curves, draw points, define variables, even define multi-argument functions. Desmos uses LaTeX for passing expressions back and forth.

                The following sections give some examples of supported functionality but are not exhaustive.

                We recommend using the interactive calculator at www.desmos.com/calculator to explore the full range of supported expressions.

                Types of expressions
                When analyzed, expressions can cause one or more of the following effects:

                Evaluation
                If the expression can be evaluated to a number, it will be evaluated

                Plotting curves
                If the expression expresses one variable as a function of another, it will be plotted.

                Plotting points
                If the expression defines one or more points, they will be plotted directly.

                Plotting Inequalities
                If an expression represents an inequality of x and y which can be solved, the entire region represented by the inequality will be shaded in.

                Exporting definitions
                Expression can export either variable or function definitions, which can be used elsewhere. Definitions are not order-dependent. Built in symbols cannot be redefined. If a symbol is defined multiple times, referencing it elsewhere will be an error.

                Solving
                If an expression of x and y can be solved (specifically, if it is quadratic in either x or y), the solution set will be plotted, but no definitions will be exported.

                Errors
                If the input cannot be interpreted, the expression will be marked as an error.

                Here are a few examples:

                input effect
                1 + 1 Evaluable.
                \sin(x) Plots y as a function of x.
                m = 1 Defines m as a variable that can be referenced by other expressions.
                a = 2 + x Plots a as a function of x, and defines a as a variable that can be referenced by other expressions.
                x + y = 3 Plots an implicit curve of x and y.
                Supported characters
                Following the LaTeX standard, any multi-character symbol must be preceded by a leading backslash, otherwise it will be interpreted as a series of single-letter variables. That the backslash also functions as an escape character inside of JavaScript strings is a common source of confusion. You should take special care to ensure that a literal backslash character ends up in the final string, which can be accomplished in one of two ways:
                \sin(\pi)
                The following functions are defined as built-ins:

                Arithmetic operators
                +, -, *, /, ^

                These operators follow standard precedence rules, and can operate on any kind of expression. As specified by LaTeX, exponentiation with a power that is more than one character (e.g. "e^{2x}") require curly braces around the exponent.

                Division is always represented in fraction notation. Curly braces can be used to specify the limits of the numerator and the denominator where they don't follow standard precedence rules.

                Mathematical constants
                e, \pi
                
                Logarithms
                \ln, \log, \sqrt{x}
                
                使用示例：
                
                绘制自定义样式的图像:
                {
                  "latex": "y=x^3-3x",
                  "color": "#ff0000",
                  lineStyle: "DASHED",
                  lineOpacity: 0.7,
                  lineWidth: 2
                }
                参数滑块:
                {
                  "latex": "a=1",
                  "sliderBounds": { "min": -5, "max": 5, "step": 0.1 }
                }

                包含参数的函数（创建带参数的方程必须要创建相应的参数滑块）：
                {
                  latex: 'y=a\\cdot x^2'
                }
                
                标记点：
                {
                  latex: '(2, 4)',
                  color: '#ff0000',
                  label: 'Maximum',
                  showLabel: true
                }
                
                """.trimIndent()),
            )
        }

        AiTools.registerTool<ShowParm>(
            name = "math",
            displayName = "绘制数学函数图像",
            description = """
                该工具用于绘制数学函数图像，包括函数曲线、方程图像等。
                该工具会将生成的数学公式直接展示给用户，若成功，会告知你成功，若不成功则告知你错误信息。
                特别要求：
                - 当你需要绘制数学方程图像等内容时（如椭圆图像），你必须使用该工具而不是svg
                - 当你要求解一个函数题目/解析几何题目前，你必须使用该工具绘制图像以直观向用户展示图像
                - 如果你需要使用复杂功能，请务必先使用`math_demo`工具学习如何使用该工具
            """.trimIndent(),
        )
        {
            AiToolInfo.ToolResult(
                Content("展示数学公式成功"),
                showingContent = aiNegotiationJson.encodeToString(it.parm),
                showingType = AiTools.ToolData.Type.MATH,
            )
        }
    }
}