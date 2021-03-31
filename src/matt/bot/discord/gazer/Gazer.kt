package matt.bot.discord.gazer

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.audit.ActionType
import net.dv8tion.jda.api.audit.TargetType
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.ShutdownEvent
import net.dv8tion.jda.api.events.guild.GuildBanEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.MessageUpdateEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import org.apache.commons.codec.digest.DigestUtils
import org.json.JSONObject
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.TimeUnit
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

val saveFile = File("saveData.json")

val adminRoles = mutableSetOf<Role>()
val suggestions = PriorityQueue<SuggestionMetaData>()

val mentionSpammers = mutableMapOf<Member, Pair<Int, Long>>()

var lastJoinTime: Instant = Instant.MAX

var shutdownMode = ExitMode.SHUTDOWN

fun main()
{
    //Logger.getLogger(OkHttpClient::class.java.name).level = Level.FINE
    val token = File("token").readText()
    bot = JDABuilder.create(token, GatewayIntent.getIntents(GatewayIntent.ALL_INTENTS))
        .addEventListeners(UtilityListener(), MessageListener())
        .build()
        .awaitReady()
    bot.addEventListener()
    
    botGuild = bot.getGuildById(guildId)!!
    
    suggestionChannel = botGuild.getTextChannelsByName("suggestions", true).firstOrNull()
    moderatorChannel = botGuild.getTextChannelsByName("moderators", true).firstOrNull()
    
    adminRoles.add(botGuild.getRoleById(adminRoleId)!!)
    adminRoles.add(botGuild.getRoleById(moderatorRoleId)!!)
    
    for(guild in bot.guilds)
        if(guild.id != guildId)
            guild.leave().queue()
    
    load()
    
    botGuild.members.filter {it.timeJoined.toInstant() > lastJoinTime}.forEach {
        val event = GuildMemberJoinEvent(bot, it.timeJoined.toInstant().epochSecond, it)
        bot.eventManager.handle(event)
    }
    
    botGuild.members.map {it.timeJoined.toInstant()}.max()?.let {lastJoinTime = it}
    
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

fun load() {
    if(!saveFile.exists())
        return
    
    val saveData = JSONObject(saveFile.readText())
    
    lastJoinTime = Instant.ofEpochSecond(saveData.getLong("lastJoinTimeSec"), saveData.getLong("lastJoinTimeNano"))
}

fun save()
{
    val saveData = JSONObject()
    
    saveData.put("lastJoinTimeSec", lastJoinTime.epochSecond)
    saveData.put("lastJoinTimeNano", lastJoinTime.nano)
    
    saveFile.writeText(saveData.toString(4))
}

class UtilityListener: ListenerAdapter()
{
    override fun onReady(event: ReadyEvent)
    {
        event.jda.isAutoReconnect = true
        println("Logged in as ${event.jda.selfUser.name}\n${event.jda.selfUser.id}\n-----------------------------")
        event.jda.presence.activity = Activity.playing("DM me suggestions or complaints for the Monster Girls discord")
    }

    override fun onShutdown(event: ShutdownEvent)
    {
        save()
        exitProcess(shutdownMode.ordinal)
    }
    
    override fun onGuildMemberJoin(event: GuildMemberJoinEvent)
    {
        lastJoinTime = maxOf(lastJoinTime, event.member.timeJoined.toInstant())
        
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
        val creationDate = member.timeCreated
        userEmbedBuilder.addField("Account Created", creationDate.toString(), true)
        if(creationDate.toInstant().isBefore(Instant.now().minus(7, ChronoUnit.DAYS)))
            userEmbedBuilder.setColor(Color.GREEN)
        else
            userEmbedBuilder.setColor(Color.RED)
        
        channel.sendMessage(userEmbedBuilder.build()).queue()
        channel.sendMessage(member.user.asMention).queue()
        
        save()
    }
    
    override fun onGuildBan(event: GuildBanEvent) {
        val channel = botGuild.getTextChannelsByName("bans", true).firstOrNull()
        if(channel == null) {
            println("bans channel is null")
            return
        }
        val user = event.user
        val embedBuilder = EmbedBuilder()
        botGuild.retrieveAuditLogs().type(ActionType.BAN).queueAfter(2, TimeUnit.SECONDS) {
            val entry = it.filter {it.targetId == user.id && it.targetType == TargetType.MEMBER}.maxBy {it.timeCreated}
            if(entry == null) {
                println("Failed to find ban in audit log. Try increasing the delay.")
            }
            else if(entry.timeCreated.toInstant().isBefore(Instant.now().minus(1, ChronoUnit.MINUTES))) {
                println("Old ban found. Not putting message in bans channel.")
            }
            else {
                embedBuilder.appendDescription(":x: **${user.name}#${user.discriminator}** was `banned`. (${user.id})\n\n*by* ${entry.user!!.asMention}\n**Reason:** ${entry.reason ?: "Not Specified"}")
                channel.sendMessage(embedBuilder.build()).queue()
            }
        }
    }
    
    override fun onGuildMemberRemove(event: GuildMemberRemoveEvent) {
        val channel = botGuild.getTextChannelsByName("bans", true).firstOrNull()
        if(channel == null) {
            println("bans channel is null")
            return
        }
        val user = event.user
        val embedBuilder = EmbedBuilder()
        botGuild.retrieveAuditLogs().type(ActionType.KICK).queueAfter(2, TimeUnit.SECONDS) {
            val entry = it.filter {it.targetId == user.id && it.targetType == TargetType.MEMBER}.maxBy {it.timeCreated}
            if(entry != null && entry.timeCreated.toInstant().isAfter(Instant.now().minus(1, ChronoUnit.MINUTES))) {
                embedBuilder.appendDescription(":x: **${user.name}#${user.discriminator}** was `kicked`. (${user.id})\n\n*by* ${entry.user!!.asMention}\n**Reason:** ${entry.reason ?: "Not Specified"}")
                channel.sendMessage(embedBuilder.build()).queue()
            }
        }
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
                val content = suggestion.contentRaw.replace("@everyone", "@\u200Beveryone")
                suggestionChannel?.sendMessage("A suggestion/complaint has been submitted with id ${suggestion.id}: $content")?.queue {
                    event.channel.sendMessage("Thank you for making a suggestion or complaint. It has been anonymously forwarded to the moderation team").queue()
                    if(suggestion.attachments.isNotEmpty())
                        event.channel.sendMessage("1 or more attachments were found in your message. Attachments are not sent as part of a suggestion. Use links instead.").queue()
                    suggestions.add(SuggestionMetaData(System.currentTimeMillis(), suggestion.id, it.id, suggestion.channel.id))
                } ?: println("suggestion channel is null")
            }
            catch(iae: IllegalArgumentException)
            {
                event.channel.sendMessage("Your suggestion or complaint was unable to be forwarded. Try a shorter message. If the problem persists contact the moderation team").queue()
            }
            
            suggestions.removeIf {System.currentTimeMillis() - it.timestamp > 86400_000}
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
            val suggestionMetaData = suggestions.first {it.suggestionMessageId == updatedSuggestion.id}
            suggestions.remove(suggestionMetaData)
            val forwardedMessage = suggestionChannel?.retrieveMessageById(suggestionMetaData.forwardedSuggestionMessageId)?.complete()
            if(forwardedMessage == null)
            {
                println("suggestion channel is null")
                return
            }
            try
            {
                val content = updatedSuggestion.contentRaw.replace("@everyone", "@\u200Beveryone")
                forwardedMessage.editMessage("A suggestion/complaint has been submitted with id ${updatedSuggestion.id}: $content").queue {
                    event.channel.sendMessage("Your suggestion or complaint has been successfully updated").queue()
                    suggestions.add(SuggestionMetaData(System.currentTimeMillis(), updatedSuggestion.id, it.id, updatedSuggestion.channel.id))
                }
            }
            catch(ise: IllegalStateException)
            {
                event.channel.sendMessage("Your suggestion or complaint was unable to be updated. Try a shorter message or sending a new message. If the problem persists contact the moderation team").queue()
                suggestions.add(suggestionMetaData.copy(timestamp = System.currentTimeMillis()))
            }
            suggestions.removeIf {System.currentTimeMillis() - it.timestamp > 86400_000}
        }
    }
}

//                                        hash     count  lastMessageTimeStamp
val spamMap = mutableMapOf<Member, Triple<ByteArray, Int, Long>>()

fun checkForSpam(event: MessageReceivedEvent)
{
    if(event.channelType == ChannelType.PRIVATE || event.channelType == ChannelType.GROUP || event.isWebhookMessage)
        return

    if(event.member == event.guild.owner || event.member!!.roles.any {it in adminRoles})
        return
    
    val isOldUser = event.member!!.timeJoined.toInstant().isBefore(Instant.now().minus(7, ChronoUnit.DAYS))
    val spamThreshold = if(isOldUser) 15 else 5
    // Checks for users that are spamming mentions
    if(countMentions(event.message) >= spamThreshold || event.message.mentionsEveryone())
    {
        var (count, lastSpamTime) = mentionSpammers.getOrDefault(event.member!!, Pair(0, 0L))
        val timeDiff = System.currentTimeMillis() - lastSpamTime
        if(timeDiff < 10_000)
            count += 1
        else
            count = 1

        // bans the user if they spammed mentions for a fifth (or more) time within the last 10 seconds
        if(count >= spamThreshold)
        {
            takeActionAgainstUser(event.member!!, true, "Spamming mentions", event.message)
            mentionSpammers.remove(event.member!!)
        }
        else
        {
            // clears users from the list of users that spammed if it's been more than 10 seconds and then adds the user that just spammed to the list
            mentionSpammers.entries.toList().forEach {(key, value) ->
                if(System.currentTimeMillis() - value.second > 10_000)
                    mentionSpammers.remove(key)
            }
            mentionSpammers[event.member!!] = Pair(count, System.currentTimeMillis())
        }
    }

    // Checks for users that are spamming the same stuff over and over again
    val repeatedMessageTime = if(isOldUser) 5_000 else 60_000
    val messageInfo = spamMap[event.member!!]
    if(messageInfo == null)
    {
        spamMap[event.member!!] = Triple(hashMessage(event.message), 1, System.currentTimeMillis())
    }
    else
    {
        val messageHash = hashMessage(event.message)
        if(messageInfo.first.contentEquals(messageHash) && System.currentTimeMillis() - messageInfo.third < repeatedMessageTime)
        {
            if(messageInfo.second == (if(isOldUser) 4 else 2))
            {
                // This message makes it the 5th (or 3rd if they're a new user)
                takeActionAgainstUser(event.member!!, true, "Spamming", event.message)
                spamMap.remove(event.member!!)
            }
            else
            {
                spamMap[event.member!!] = Triple(messageHash, messageInfo.second + 1, System.currentTimeMillis())
            }
        }
        else
        {
            spamMap[event.member!!] = Triple(hashMessage(event.message), 1, System.currentTimeMillis())
            spamMap.entries.toList().forEach {(key, value) ->
                if(System.currentTimeMillis() - value.third > repeatedMessageTime)
                    spamMap.remove(key)
            }
        }
    }
}

fun takeActionAgainstUser(member: Member, ban: Boolean, reason: String, message: Message)
{
    val actionRole = member.guild.getRoleById(detentionRoleId)!!
    if(actionRole !in member.roles)
    {
        member.guild.addRoleToMember(member, actionRole).queue()

        moderatorChannel?.let {
            it.sendMessage(adminRoles.joinToString(" ", postfix = "\n${member.asMention} has been detected spamming in <#${message.channel.id}> and was given ${actionRole.asMention}. Here is the message that triggered this: ${message.jumpUrl}") {it.asMention}).queue()
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
    messageDigest.update(message.channel.name.toByteArray())
    messageDigest.update(message.contentRaw.toByteArray())
    message.attachments.forEach {
        retryOrReturn(10, Unit) {
            val byteArray = it.retrieveInputStream().get().use {it.toByteArray()}
            messageDigest.update(byteArray)
        }
    }
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