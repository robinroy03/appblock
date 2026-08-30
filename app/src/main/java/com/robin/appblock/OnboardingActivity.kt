package com.robin.appblock

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The manifesto: shown once on first launch, and re-openable from the About
 * dialog. Explains why the app rations usage per time window instead of
 * enforcing a daily quota.
 */
class OnboardingActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Tie yourself to the mast"

        val body = TextView(this).apply {
            textSize = 16f
            setLineSpacing(0f, 1.2f)
            text = "Odysseus had to sail past the Sirens, whose song lured " +
                "every sailor to shipwreck. Every captain before him knew " +
                "the danger. Knowing was never enough.\n\n" +
                "Odysseus wasn't stronger than the others. He was wiser: he " +
                "knew that in the moment, with the song in his ears, he'd be " +
                "as weak as anyone. So he had his crew tie him to the mast " +
                "before the singing started.\n\n" +
                "Your phone sings too. And you already know knowing isn't " +
                "enough. You've promised yourself \"just five minutes\" " +
                "before.\n\n" +
                "AppBlock is your mast. You decide the rules now, while " +
                "you're clearheaded: a few minutes per app, every few hours. " +
                "Later, when the pull comes, the decision is already made. " +
                "Not zero access, no daily quota to blow through by 5am. " +
                "Just enough to check in, never enough to drown."
        }
        val next = Button(this).apply {
            text = "Next"
            setOnClickListener {
                Storage.setOnboardingSeen(this@OnboardingActivity)
                finish()
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
            addView(body)
            addView(next)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
