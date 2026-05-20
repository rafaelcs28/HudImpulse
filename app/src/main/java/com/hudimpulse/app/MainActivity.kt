package com.hudimpulse.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings screen for HUD Impulse.
 * All visual properties of the HUD display are customizable here.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: HudPreferences
    private var previewArrow: ImageView? = null
    private var previewDistance: TextView? = null
    private var previewAction: TextView? = null
    private var previewStreet: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = HudPreferences(this)
        buildSettingsUi()
    }

    private fun buildSettingsUi() {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ── Title ──
        layout.addView(makeTitle("HUD Impulse"))

        // ── Start HUD Button ──
        layout.addView(Button(this).apply {
            text = "START HUD DISPLAY"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#4FC3F7"))
            textSize = 18f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, HudDisplayActivity::class.java))
            }
        }.also {
            (it.layoutParams as? LinearLayout.LayoutParams)?.apply { bottomMargin = dp(24) }
        })

        // ── Preview ──
        layout.addView(makeSectionHeader("Preview"))
        layout.addView(buildPreview())

        // ── Arrow Settings ──
        layout.addView(makeSectionHeader("Arrow"))
        layout.addView(makeToggle("Show Arrow", prefs.showArrow) { prefs.showArrow = it; updatePreview() })
        layout.addView(makeSlider("Arrow Size", prefs.arrowSize, 20, 120) { prefs.arrowSize = it; updatePreview() })

        // ── Text Settings ──
        layout.addView(makeSectionHeader("Text"))
        layout.addView(makeSlider("Font Size (sp)", prefs.fontSize, 8, 60) { prefs.fontSize = it; updatePreview() })
        layout.addView(makeSlider("Distance Font (sp)", prefs.distanceFontSize, 8, 60) { prefs.distanceFontSize = it; updatePreview() })
        layout.addView(makeToggle("Show Distance", prefs.showDistance) { prefs.showDistance = it; updatePreview() })
        layout.addView(makeToggle("Show Street Name", prefs.showStreetName) { prefs.showStreetName = it; updatePreview() })

        // ── Color ──
        layout.addView(makeSectionHeader("Colors"))
        val colorOptions = arrayOf("White", "Green", "Blue", "Yellow", "Red", "Cyan")
        val colorValues = intArrayOf(
            Color.WHITE, Color.parseColor("#4CAF50"), Color.parseColor("#4FC3F7"),
            Color.parseColor("#FFEB3B"), Color.parseColor("#F44336"), Color.parseColor("#00BCD4")
        )
        layout.addView(makeColorPicker("Text Color", prefs.textColor, colorOptions, colorValues) { prefs.textColor = it; updatePreview() })
        layout.addView(makeColorPicker("Arrow Color", prefs.arrowColor, colorOptions, colorValues) { prefs.arrowColor = it; updatePreview() })

        // ── Layout ──
        layout.addView(makeSectionHeader("Position"))
        val hAlignOptions = arrayOf("Left", "Center", "Right")
        layout.addView(makeRadioGroup("Horizontal", prefs.horizontalAlign, hAlignOptions) { prefs.horizontalAlign = it })
        val vAlignOptions = arrayOf("Top", "Center", "Bottom")
        layout.addView(makeRadioGroup("Vertical", prefs.verticalAlign, vAlignOptions) { prefs.verticalAlign = it })
        layout.addView(makeSlider("H Padding (dp)", prefs.paddingH, 0, 100) { prefs.paddingH = it })
        layout.addView(makeSlider("V Padding (dp)", prefs.paddingV, 0, 100) { prefs.paddingV = it })

        // ── Display ──
        layout.addView(makeSectionHeader("Display"))
        layout.addView(makeNumberInput("Width (px)", prefs.displayWidth) { prefs.displayWidth = it })
        layout.addView(makeNumberInput("Height (px)", prefs.displayHeight) { prefs.displayHeight = it })
        layout.addView(makeNumberInput("DPI", prefs.displayDpi) { prefs.displayDpi = it })

        // ── Background ──
        layout.addView(makeSectionHeader("Background"))
        layout.addView(makeToggle("Transparent (no semi-dark)", prefs.backgroundTransparent) { prefs.backgroundTransparent = it })

        // ── NOVO: Automation (Auto-start) ──
        layout.addView(makeSectionHeader("Automation"))
        layout.addView(makeToggle("Auto-start on Connect", prefs.autoStartOnConnect) {
            prefs.autoStartOnConnect = it
        })
        layout.addView(TextView(this).apply {
            text = "Automatically opens HUD when Headunit Revived connects."
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
            setPadding(0, 0, 0, dp(16))
        })

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun buildPreview(): FrameLayout {
        val preview = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            minimumHeight = dp(200)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200)
            ).apply { bottomMargin = dp(16) }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        previewArrow = ImageView(this).apply {
            val bmp = ArrowDrawer.drawArrow(4, 2, dp(prefs.arrowSize), prefs.arrowColor) // Turn right
            setImageBitmap(bmp)
            layoutParams = LinearLayout.LayoutParams(dp(prefs.arrowSize), dp(prefs.arrowSize)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        container.addView(previewArrow)

        previewDistance = TextView(this).apply {
            text = "270 m"
            setTextColor(prefs.textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.distanceFontSize.toFloat())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        container.addView(previewDistance, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); gravity = Gravity.CENTER_HORIZONTAL })

        previewAction = TextView(this).apply {
            text = "Turn right"
            setTextColor(prefs.textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.fontSize.toFloat())
            gravity = Gravity.CENTER
        }
        container.addView(previewAction, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); gravity = Gravity.CENTER_HORIZONTAL })

        previewStreet = TextView(this).apply {
            text = "Av. Paulista"
            setTextColor(prefs.textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (prefs.fontSize * 0.75f))
            gravity = Gravity.CENTER
        }
        container.addView(previewStreet, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2); gravity = Gravity.CENTER_HORIZONTAL })

        preview.addView(container, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))

        return preview
    }

    private fun updatePreview() {
        previewArrow?.let {
            val bmp = ArrowDrawer.drawArrow(4, 2, dp(prefs.arrowSize), prefs.arrowColor)
            it.setImageBitmap(bmp)
            it.layoutParams = LinearLayout.LayoutParams(dp(prefs.arrowSize), dp(prefs.arrowSize)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            it.visibility = if (prefs.showArrow) View.VISIBLE else View.GONE
        }
        previewDistance?.apply {
            setTextColor(prefs.textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.distanceFontSize.toFloat())
            visibility = if (prefs.showDistance) View.VISIBLE else View.GONE
        }
        previewAction?.apply {
            setTextColor(prefs.textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.fontSize.toFloat())
        }
        previewStreet?.apply {
            setTextColor(prefs.textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (prefs.fontSize * 0.75f))
            visibility = if (prefs.showStreetName) View.VISIBLE else View.GONE
        }
    }

    // ── UI Helpers ──

    private fun makeTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 28f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(16))
    }

    private fun makeSectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(Color.parseColor("#4FC3F7"))
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(8))
    }

    private fun makeToggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))

            addView(TextView(this@MainActivity).apply {
                text = label; setTextColor(Color.WHITE); textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Switch(this@MainActivity).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            })
        }
    }

    private fun makeSlider(label: String, initial: Int, min: Int, max: Int, onChange: (Int) -> Unit): View {
        val valueText = TextView(this).apply {
            text = "$initial"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 14f
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@MainActivity).apply {
                    text = label; setTextColor(Color.WHITE); textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(valueText)
            })
            addView(SeekBar(this@MainActivity).apply {
                this.max = max - min
                progress = initial - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = progress + min
                        valueText.text = "$value"
                        onChange(value)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }
    }

    private fun makeColorPicker(label: String, current: Int, names: Array<String>, values: IntArray, onChange: (Int) -> Unit): View {
        val currentIdx = values.indexOfFirst { it == current }.coerceAtLeast(0)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))

            addView(TextView(this@MainActivity).apply {
                text = label; setTextColor(Color.WHITE); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
                setSelection(currentIdx)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        (view as? TextView)?.setTextColor(Color.WHITE)
                        onChange(values[pos])
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            })
        }
    }

    private fun makeRadioGroup(label: String, current: Int, options: Array<String>, onChange: (Int) -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))

            addView(TextView(this@MainActivity).apply {
                text = label; setTextColor(Color.WHITE); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
            })

            val rg = RadioGroup(this@MainActivity).apply {
                orientation = RadioGroup.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
            }
            options.forEachIndexed { index, name ->
                rg.addView(RadioButton(this@MainActivity).apply {
                    text = name
                    setTextColor(Color.WHITE)
                    id = index + 100
                    isChecked = index == current
                })
            }
            rg.setOnCheckedChangeListener { _, checkedId -> onChange(checkedId - 100) }
            addView(rg)
        }
    }

    private fun makeNumberInput(label: String, initial: Int, onChange: (Int) -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))

            addView(TextView(this@MainActivity).apply {
                text = label; setTextColor(Color.WHITE); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(EditText(this@MainActivity).apply {
                setText("$initial")
                setTextColor(Color.WHITE)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setBackgroundColor(Color.parseColor("#333333"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val value = text.toString().toIntOrNull() ?: initial
                        onChange(value)
                    }
                }
            })
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
    }
}
