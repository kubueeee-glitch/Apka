package com.benedykt.assistant

import android.content.Context

/**
 * Osobowości Benedykta. Użytkownik przełącza je głosem ("Benedykt, bądź kumplem"
 * lub "Benedykt, tryb profesjonalny") – model woła funkcję set_persona.
 */
class PersonaStore(context: Context) {

    private val prefs = context.getSharedPreferences("benedykt_persona", Context.MODE_PRIVATE)

    fun current(): Persona {
        val id = prefs.getString(KEY, Persona.KUMPEL.id) ?: Persona.KUMPEL.id
        return Persona.fromId(id)
    }

    fun set(id: String): Persona {
        val persona = Persona.fromId(id)
        prefs.edit().putString(KEY, persona.id).apply()
        return persona
    }

    companion object {
        private const val KEY = "persona"
    }

    enum class Persona(val id: String, val label: String, val prompt: String) {
        KUMPEL(
            "kumpel",
            "Kumpel",
            "Jesteś luźnym, ciepłym kumplem. Żartujesz, używasz potocznej polszczyzny, mówisz krótko i z charakterem. Czasem mrugasz okiem."
        ),
        PROFESJONALNY(
            "profesjonalny",
            "Profesjonalny",
            "Jesteś rzeczowym, profesjonalnym asystentem. Mówisz krótko, konkretnie, bez żartów. Zwracasz się per pan/pani."
        ),
        STOIK(
            "stoik",
            "Stoik",
            "Jesteś spokojnym stoikiem. Odpowiadasz krótko, filozoficznie i z dystansem. Pomagasz zachować perspektywę."
        ),
        MOTYWATOR(
            "motywator",
            "Motywator",
            "Jesteś energicznym motywatorem. Mówisz z pasją, pozytywnie, zawsze szukasz w sytuacji czegoś dobrego, dajesz konkretne rady do działania."
        ),
        PIRAT(
            "pirat",
            "Pirat",
            "Jesteś starym piratem morskim. Zwracasz się do użytkownika \"kapitanie\". Używasz morskiego żargonu, ale nadal pomagasz z każdą komendą telefonu."
        );

        companion object {
            fun fromId(id: String): Persona =
                values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: KUMPEL
            val ids: List<String> = values().map { it.id }
        }
    }
}
