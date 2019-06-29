package matt.bot.discord.gazer

import net.dv8tion.jda.core.AccountType
import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.JDABuilder
import net.dv8tion.jda.core.entities.*
import net.dv8tion.jda.core.events.ReadyEvent
import net.dv8tion.jda.core.events.ShutdownEvent
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.events.message.MessageUpdateEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import org.apache.commons.codec.digest.DigestUtils
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.*
import kotlin.system.exitProcess

lateinit var bot: JDA
    private set
lateinit var botGuild: Guild
    private set
lateinit var suggestionChannel: TextChannel
    private set

const val botPrefix = "ga!"
const val guildId = "174678310868090880"

const val moderatorChannelId = "270733523952861186"
const val suggestionChannelId = "575858125819871242"
const val detentionChannelId = "390301025384529921"

const val adminRoleId = "174678690012069888"
const val moderatorRoleId = "198597919836733441"
const val detentionRoleId = "390225048709103638"

val adminRoles = mutableSetOf<Role>()
val suggestions = PriorityQueue<Triple<Long, String, String>> {t1, t2 -> t1.first.compareTo(t2.first)}

val mentionSpammers = mutableMapOf<Member, Pair<Int, Long>>()

var shutdownMode = ExitMode.SHUTDOWN

fun main(args: Array<String>)
{
    val token = File("token").readText()
    bot = JDABuilder(AccountType.BOT)
        .setToken(token)
        .addEventListener(UtilityListener(), MessageListener())
        .buildBlocking()
    bot.addEventListener()
    
    botGuild = bot.getGuildById(guildId)
    
    suggestionChannel = botGuild.getTextChannelById(suggestionChannelId)
    
    adminRoles.add(botGuild.getRoleById(adminRoleId))
    adminRoles.add(botGuild.getRoleById(moderatorRoleId))
    
    while(true)
    {
        try
        {
            commandLine(bot)
        }
        catch(e: Exception)
        {
            e.printStackTrace()
        }
    }
}

fun save()
{
    // Currently not used
}

class UtilityListener: ListenerAdapter()
{
    override fun onReady(event: ReadyEvent)
    {
        event.jda.isAutoReconnect = true
        println("Logged in as ${event.jda.selfUser.name}\n${event.jda.selfUser.id}\n-----------------------------")
        event.jda.presence.game = Game.playing("DM me suggestions or complaints for the Monster Girls discord")
    }

    override fun onShutdown(event: ShutdownEvent)
    {
        save()
        exitProcess(shutdownMode.ordinal)
    }
}

class MessageListener: ListenerAdapter()
{
    override fun onMessageReceived(event: MessageReceivedEvent)
    {
        if(event.author.isBot)
            return

        val privateChannel = !event.channelType.isGuild
        if(!privateChannel)
        {
            // Spam checking
            checkForSpam(event)
        }

        val tokenizer = Tokenizer(event.message.contentRaw)
        if(!tokenizer.hasNext())
            return

        val firstToken = tokenizer.next()
        if(firstToken.tokenType == TokenType.COMMAND)
        {
            runCommand(firstToken.tokenValue, tokenizer, event.message)
        }
        else if(privateChannel)
        {
            val suggestion = event.message
            try
            {
                suggestionChannel.sendMessage("A suggestion/complaint has been submitted: ${suggestion.contentRaw}").queue {
                    event.channel.sendMessage("Thank you for making a suggestion or complaint. It has been anonymously forwarded to the moderation team").queue()
                    suggestions.add(Triple(System.currentTimeMillis(), suggestion.id, it.id))
                }
            }
            catch(iae: IllegalArgumentException)
            {
                event.channel.sendMessage("Your suggestion or complaint was unable to be forwarded. Try a shorter message. If the problem persists contact the moderation team").queue()
            }
            
            suggestions.removeIf {System.currentTimeMillis() - it.first > 60_000}
        }
    }
    
    override fun onMessageUpdate(event: MessageUpdateEvent)
    {
        if(event.author.isBot)
            return
        
        val privateChannel = !event.channelType.isGuild
        if(privateChannel)
        {
            val updatedSuggestion = event.message
            val suggestionMetaData = suggestions.first {it.second == updatedSuggestion.id}
            suggestions.remove(suggestionMetaData)
            val forwardedMessage = suggestionChannel.getMessageById(suggestionMetaData.third).complete()
            try
            {
                forwardedMessage.editMessage("A suggestion/complaint has been submitted: ${updatedSuggestion.contentRaw}").queue {
                    event.channel.sendMessage("Your suggestion or complaint has been successfully updated").queue()
                    suggestions.add(Triple(System.currentTimeMillis(), updatedSuggestion.id, it.id))
                }
            }
            catch(ise: IllegalStateException)
            {
                event.channel.sendMessage("Your suggestion or complaint was unable to be updated. Try a shorter message or sending a new message. If the problem persists contact the moderation team").queue()
                suggestions.add(suggestionMetaData.copy(System.currentTimeMillis()))
            }
            suggestions.removeIf {System.currentTimeMillis() - it.first > 60_000}
        }
    }
}

//                                        hash     count  lastMessageTimeStamp
val spamMap = mutableMapOf<Member, Triple<ByteArray, Int, Long>>()

fun checkForSpam(event: MessageReceivedEvent)
{
    if(event.channelType == ChannelType.PRIVATE || event.channelType == ChannelType.GROUP || event.isWebhookMessage)
        return

    if(event.member == event.guild.owner || event.member.roles.any {it in adminRoles})
        return

    // Checks for users that are spamming mentions
    if(countMentions(event.message) >= 5 || event.message.mentionsEveryone())
    {
        var (count, lastSpamTime) = mentionSpammers.getOrDefault(event.member, Pair(0, 0L))
        val timeDiff = System.currentTimeMillis() - lastSpamTime
        if(timeDiff < 10_000)
            count += 1
        else
            count = 1

        // bans the user if they spammed mentions for a fifth (or more) time within the last 10 seconds
        if(count >= 5)
        {
            takeActionAgainstUser(event.member, true, "Spamming mentions")
            mentionSpammers.remove(event.member)
        }
        else
        {
            // clears users from the list of users that spammed if it's been more than 10 seconds and then adds the user that just spammed to the list
            mentionSpammers.entries.toList().forEach {(key, value) ->
                if(System.currentTimeMillis() - value.second > 10_000)
                    mentionSpammers.remove(key)
            }
            mentionSpammers[event.member] = Pair(count, System.currentTimeMillis())
        }
    }

    // Checks for users that are spamming the same stuff over and over again
    val messageInfo = spamMap[event.member]
    if(messageInfo == null)
    {
        spamMap[event.member] = Triple(hashMessage(event.message), 1, System.currentTimeMillis())
    }
    else
    {
        val messageHash = hashMessage(event.message)
        if(messageInfo.first.contentEquals(messageHash) && System.currentTimeMillis() - messageInfo.third < 5_000)
        {
            if(messageInfo.second == 9)
            {
                // This message makes it the 10th
                takeActionAgainstUser(event.member, true, "Spamming")
                spamMap.remove(event.member)
            }
            else
            {
                spamMap[event.member] = Triple(messageHash, messageInfo.second + 1, System.currentTimeMillis())
            }
        }
        else
        {
            spamMap[event.member] = Triple(hashMessage(event.message), 1, System.currentTimeMillis())
            spamMap.entries.toList().forEach {(key, value) ->
                if(System.currentTimeMillis() - value.third > 5_000)
                    spamMap.remove(key)
            }
        }
    }
}

fun takeActionAgainstUser(member: Member, ban: Boolean, reason: String)
{
    val actionRole = member.guild.getRoleById(detentionRoleId)
    if(actionRole !in member.roles)
    {
        member.guild.controller.addSingleRoleToMember(member, actionRole).queue()

        member.guild.getTextChannelById(moderatorChannelId).sendMessage(adminRoles.joinToString(" ", postfix = "\n${member.asMention} has been detected spamming and was given ${actionRole.asMention}") {it.asMention}).queue()

        val detentionChannel = member.guild.getTextChannelById(detentionChannelId)
        detentionChannel.sendMessage("${member.asMention} You have been put in detention because you are suspected of spamming. If this is a mistake then an admin or mod will be along shortly to fix it. If not, make your appeal here. You will be unable to post in other channels until this is resolved.").queue()
    }
}

fun hashMessage(message: Message): ByteArray
{
    val messageDigest = DigestUtils.getSha256Digest()
    messageDigest.update(message.contentRaw.toByteArray())
    message.attachments.forEach {messageDigest.update(retry(10) {it.inputStream.toByteArray()})}
    return messageDigest.digest()
}

fun InputStream.toByteArray(): ByteArray
{
    if(this !is ByteArrayInputStream)
        return this.readBytes()

    val field = ByteArrayInputStream::class.java.getDeclaredField("buf")
    field.isAccessible = true
    return field.get(this) as ByteArray
}