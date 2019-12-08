package matt.bot.discord.gazer

import net.dv8tion.jda.core.AccountType
import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.JDABuilder
import net.dv8tion.jda.core.entities.*
import net.dv8tion.jda.core.events.ReadyEvent
import net.dv8tion.jda.core.events.ShutdownEvent
import net.dv8tion.jda.core.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.events.message.MessageUpdateEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import org.apache.commons.codec.digest.DigestUtils
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.system.exitProcess

lateinit var bot: JDA
    private set
lateinit var botGuild: Guild
    private set
var suggestionChannel: TextChannel? = null
    private set
var moderatorChannel: TextChannel? = null
    private set

const val botPrefix = "ga!"
const val guildId = "174678310868090880"

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
        .build()
        .awaitReady()
    bot.addEventListener()
    
    botGuild = bot.getGuildById(guildId)
    
    suggestionChannel = botGuild.getTextChannelsByName("suggestions", true).firstOrNull()
    moderatorChannel = botGuild.getTextChannelsByName("moderators", true).firstOrNull()
    
    adminRoles.add(botGuild.getRoleById(adminRoleId))
    adminRoles.add(botGuild.getRoleById(moderatorRoleId))
    
    for(guild in bot.guilds)
        if(guild.id != guildId)
            guild.leave().queue()
    
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
    
    override fun onGuildMemberJoin(event: GuildMemberJoinEvent)
    {
        val channel = botGuild.getTextChannelsByName("joins", true).firstOrNull()
        if(channel == null)
        {
            println("joins channel is null")
            return
        }
        val member = event.member
        val userEmbedBuilder = EmbedBuilder()
        userEmbedBuilder.setAuthor("${member.user.name}#${member.user.discriminator}", null, member.user.effectiveAvatarUrl)
        userEmbedBuilder.setThumbnail(member.user.effectiveAvatarUrl)
        userEmbedBuilder.addField("ID", member.user.id, true)
        val creationDate = Date(member.user.idLong / 4194304L + 1420070400000L)
        userEmbedBuilder.addField("Account Created", creationDate.toString(), true)
        if(creationDate.toInstant().isBefore(Instant.now().minus(7, ChronoUnit.DAYS)))
            userEmbedBuilder.setColor(Color.GREEN)
        else
            userEmbedBuilder.setColor(Color.RED)
        
        channel.sendMessage(userEmbedBuilder.build()).queue()
        channel.sendMessage(member.user.asMention).queue()
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
                suggestionChannel?.sendMessage("A suggestion/complaint has been submitted: ${suggestion.contentRaw}")?.queue {
                    event.channel.sendMessage("Thank you for making a suggestion or complaint. It has been anonymously forwarded to the moderation team").queue()
                    if(suggestion.attachments.isNotEmpty())
                        event.channel.sendMessage("1 or more attachments were found in your message. Attachments are not sent as part of a suggestion. Use links instead.").queue()
                    suggestions.add(Triple(System.currentTimeMillis(), suggestion.id, it.id))
                } ?: println("suggestion channel is null")
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
            val forwardedMessage = suggestionChannel?.getMessageById(suggestionMetaData.third)?.complete()
            if(forwardedMessage == null)
            {
                println("suggestion channel is null")
                return
            }
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
            takeActionAgainstUser(event.member, true, "Spamming mentions", event.message)
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
            if(messageInfo.second == 4)
            {
                // This message makes it the 5th
                takeActionAgainstUser(event.member, true, "Spamming", event.message)
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

fun takeActionAgainstUser(member: Member, ban: Boolean, reason: String, message: Message)
{
    val actionRole = member.guild.getRoleById(detentionRoleId)
    if(actionRole !in member.roles)
    {
        member.guild.controller.addSingleRoleToMember(member, actionRole).queue()

        moderatorChannel?.let {
            it.sendMessage(adminRoles.joinToString(" ", postfix = "\n${member.asMention} has been detected spamming in <#${message.channel.id}> and was given ${actionRole.asMention}") {it.asMention}).queue()
        } ?: println("moderator channel is null")

        val detentionChannel = member.guild.getTextChannelsByName("detention", true).firstOrNull()
        if(detentionChannel != null)
            detentionChannel.sendMessage("${member.asMention} You have been put in detention because you are suspected of spamming. If this is a mistake then an admin or mod will be along shortly to fix it. If not, make your appeal here. You will be unable to post in other channels until this is resolved.").queue()
        else
            println("The detention channel does not exist")
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