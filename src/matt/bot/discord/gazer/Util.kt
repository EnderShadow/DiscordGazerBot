package matt.bot.discord.gazer

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message

fun countMentions(message: Message) = message.mentionedChannels.size + message.mentionedRoles.size + message.mentionedUsers.size + if(message.mentionsEveryone()) 1 else 0

fun isServerAdmin(member: Member) = member.isOwner || member.roles.intersect(adminRoles).isNotEmpty() || member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.MANAGE_SERVER)

fun reloadBot(bot: JDA)
{
    shutdownMode = ExitMode.RELOAD
    bot.shutdown()
}

fun shutdownBot(bot: JDA)
{
    shutdownMode = ExitMode.SHUTDOWN
    bot.shutdown()
}

/**
 * All items matching the filter are put into the first list. All items not matching the filter are put into the second list
 */
inline fun <reified T, reified U> Collection<T>.splitAndMap(filter: (T) -> Boolean, mapper: (T) -> (U)): Pair<List<U>, List<U>>
{
    val l1 = mutableListOf<U>()
    val l2 = mutableListOf<U>()
    forEach {
        if(filter(it))
            l1.add(mapper(it))
        else
            l2.add(mapper(it))
    }
    return Pair(l1, l2)
}

inline fun <reified T> retry(amt: Int, provider: () -> T): T
{
    if(amt <= 0)
    {
        while(true)
        {
            try
            {
                return provider()
            }
            catch(_: Throwable) {}
        }
    }
    else
    {
        var lastThrowable = Throwable() // unused throwable that is used to prevent the compiler from complaining about an impossible lack of initialization before use
        for(i in 0 until amt)
        {
            try
            {
                return provider()
            }
            catch(t: Throwable)
            {
                lastThrowable = t
            }
        }
        throw lastThrowable
    }
}

fun String.containsSparse(text: String): Boolean
{
    if(text.length > length)
        return false
    var index = 0
    for(c in text)
    {
        index = indexOf(c, index) + 1
        if(index <= 0)
            return false
    }
    return true
}