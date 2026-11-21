package com.example.vkweb.messages

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import com.example.vkweb.R
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.ui.Views

/**
 * Строки списка участников беседы (4.33): аватар (нет фото — кружок с инициалами, как в списке диалогов) и имя.
 * Для MaterialAlertDialogBuilder.setAdapter.
 */
class ChatPeopleList(context: Context, private val people: List<ChatPeople.Person>) :
    ArrayAdapter<ChatPeople.Person>(context, R.layout.item_chat_person, people) {

    private val images = ImageCache.get(context)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_chat_person, parent, false).also {
            val avatar = it.findViewById<ImageView>(R.id.chat_person_avatar)
            Views.clipRounded(avatar, Views.dp(avatar, AVATAR_DP / 2).toFloat())
        }
        val person = people[position]
        val avatar = view.findViewById<ImageView>(R.id.chat_person_avatar)
        view.findViewById<TextView>(R.id.chat_person_name).text = person.name
        images.load(avatar, person.avatar.ifEmpty { null }, Views.dp(avatar, AVATAR_DP)) { AvatarBadge(person.name) }
        return view
    }

    private companion object {
        const val AVATAR_DP = 40
    }
}
