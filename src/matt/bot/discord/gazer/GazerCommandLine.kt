package matt.bot.discord.gazer

import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.MessageBuilder
import net.dv8tion.jda.core.entities.*
import net.dv8tion.jda.core.requests.Request
import net.dv8tion.jda.core.requests.RequestFuture
import net.dv8tion.jda.core.requests.Response
import net.dv8tion.jda.core.requests.RestAction
import net.dv8tion.jda.core.requests.restaction.AuditableRestAction
import net.dv8tion.jda.core.requests.restaction.MessageAction
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

fun commandLine(bot: JDA)
{
    var selectedTextChannel: TextChannel? = null
    var selectedMember: Member? = null
    
    val scanner = Scanner(System.`in`)
    lineLoop@while(true)
    {
        val line = scanner.nextLine()
        val tokenizer = Tokenizer(line)
        if(!tokenizer.hasNext())
            continue
        
        val command = tokenizer.next().tokenValue
        when(command)
        {
            "shutdown" -> shutdownBot(bot)
            "reload" -> reloadBot(bot)
            "ping" -> println("pong")
            "resetPresence" -> {
                bot.presence.game = Game.playing("DM me suggestions or complaints for the Monster Girls discord")
                println("Presence has been reset")
            }
            "selectedTextChannel" -> println(selectedTextChannel?.name)
            "selectedMember" -> println(selectedMember?.effectiveName)
            "list" -> {
                if(tokenizer.hasNext())
                {
                    when(tokenizer.next().tokenValue)
                    {
                        "textChannels" -> {
                            botGuild.textChannels.map {it.name}.forEach {println(it)}
                        }
                        "members" -> {
                            val users = botGuild.members.toMutableList()
                            if(tokenizer.hasNext())
                            {
                                val filterString = tokenizer.remainingTextAsToken.rawValue
                                users.removeIf {!it.effectiveName.containsSparse(filterString)}
                            }
                            users.forEach {println("${it.user.id}\t${it.effectiveName}")}
                        }
                        else -> println("Unknown command")
                    }
                }
                else
                {
                    println("Not enough command arguments")
                }
            }
            "select" -> {
                if(!tokenizer.hasNext())
                {
                    println("Not enough command arguments")
                    continue@lineLoop
                }
                val category = tokenizer.next().tokenValue
                if(!tokenizer.hasNext())
                {
                    println("Not enough command arguments")
                    continue@lineLoop
                }
                val value = tokenizer.remainingTextAsToken.tokenValue
                when(category)
                {
                    "textChannel" -> {
                        botGuild.textChannels.firstOrNull {it.name == value}?.let {selectedTextChannel = it} ?: println("Cannot find text channel with name \"$value\"")
                    }
                    "member" -> {
                        selectedMember = botGuild.members.firstOrNull {it.user.id == value}
                    }
                }
            }
            "type" -> {
                if(selectedTextChannel == null)
                    println("Select a text channel first")
                else
                    MessageBuilder(tokenizer.remainingTextAsToken.tokenValue).sendTo(selectedTextChannel).queue()
            }
            "sudo" -> {
                runCommand(tokenizer.next().tokenValue, tokenizer, CommandLineMessage(botGuild, selectedMember, selectedTextChannel, line))
            }
            "pullMessage" -> {
                if(!tokenizer.hasNext())
                {
                    println("You need to enter a message ID")
                }
                else
                {
                    val id = tokenizer.next().tokenValue
                    val message = getMessage(id)
                    if(message != null)
                    {
                        val messageFile = File("pulledMessages/$id")
                        messageFile.parentFile.mkdirs()
                        messageFile.writeText(message.contentRaw)
                        println("Seccessfully pulled message")
                    }
                    else
                    {
                        println("Cannot find message")
                    }
                }
            }
            "pushMessage" -> {
                if(!tokenizer.hasNext())
                {
                    println("You need to enter a message ID")
                }
                else
                {
                    val id = tokenizer.next().tokenValue
                    val message = getMessage(id)
                    val messageFile = File("pulledMessages/$id")
                    if(message != null && message.author == bot.selfUser && messageFile.exists())
                    {
                        message.editMessage(messageFile.readText()).complete()
                        messageFile.delete()
                        messageFile.parentFile.takeIf {it.list()?.isEmpty() == true}?.delete()
                        println("Successfully pushed message")
                    }
                    else
                    {
                        println("Either the message doesn't exist, you are not the author of this message, or you haven't pulled the message yet")
                    }
                }
            }
        }
    }
}

private fun getMessage(id: String): Message?
{
    return bot.textChannels.asSequence().map {
        try
        {
            it.getMessageById(id).complete()
        }
        catch(e: Exception)
        {
            null
        }
    }.filterNotNull().firstOrNull()
}

private class CommandLineMessage(private val guild: Guild, private val member: Member?, private val textChannel: TextChannel?, private val text: String): Message
{
    override fun isFromType(type: ChannelType?) = type == textChannel?.type ?: ChannelType.UNKNOWN
    override fun getGroup() = null
    override fun isEdited() = false
    override fun isPinned() = false
    override fun mentionsEveryone() = false
    override fun addReaction(emote: Emote?): RestAction<Void> = FakeAuditableRestAction()
    override fun addReaction(unicode: String?): RestAction<Void> = FakeAuditableRestAction()
    override fun clearReactions(): RestAction<Void> = FakeAuditableRestAction()
    override fun formatTo(p0: Formatter?, p1: Int, p2: Int, p3: Int) {}
    override fun getContentRaw() = text
    override fun getContentStripped() = text
    override fun getGuild() = guild
    override fun isTTS() = false
    override fun isMentioned(mentionable: IMentionable?, vararg types: Message.MentionType?) = false
    override fun editMessageFormat(format: String?, vararg args: Any?): MessageAction = FakeMessageAction(textChannel)
    override fun getMentionedChannels() = mutableListOf<TextChannel>()
    override fun getMember(): Member = member ?: guild.owner
    override fun getIdLong() = 0L
    override fun getContentDisplay() = text
    override fun getPrivateChannel(): PrivateChannel? = null
    override fun getChannelType() = textChannel?.type ?: ChannelType.UNKNOWN
    override fun getAttachments() = mutableListOf<Message.Attachment>()
    override fun getMentionedRoles() = mutableListOf<Role>()
    override fun pin(): RestAction<Void>? = FakeAuditableRestAction()
    override fun getMentionedMembers(guild: Guild?) = mutableListOf<Member>()
    override fun getMentionedMembers() = mutableListOf<Member>()
    override fun unpin(): RestAction<Void>? = FakeAuditableRestAction()
    override fun getCategory(): Category? = null
    override fun getInvites() = mutableListOf<String>()
    override fun getEditedTime(): OffsetDateTime = OffsetDateTime.MAX
    override fun getMentionedUsers() = mutableListOf<User>()
    override fun getEmotes() = mutableListOf<Emote>()
    override fun getAuthor(): User = guild.owner.user
    override fun editMessage(newContent: CharSequence?): MessageAction = FakeMessageAction(textChannel)
    override fun editMessage(newContent: MessageEmbed?): MessageAction = FakeMessageAction(textChannel)
    override fun editMessage(newContent: Message?): MessageAction = FakeMessageAction(textChannel)
    override fun delete(): AuditableRestAction<Void> = FakeAuditableRestAction()
    override fun getMentions(vararg types: Message.MentionType?) = mutableListOf<IMentionable>()
    override fun isWebhookMessage() = false
    override fun getEmbeds() = mutableListOf<MessageEmbed>()
    override fun getType() = MessageType.UNKNOWN
    override fun getChannel(): MessageChannel? = textChannel
    override fun getJDA() = bot
    override fun getReactions() = mutableListOf<MessageReaction>()
    override fun getTextChannel(): TextChannel? = textChannel
    override fun getNonce() = ""
}

private class FakeAuditableRestAction<T>: AuditableRestAction<T>(bot, null)
{
    override fun handleResponse(response: Response?, request: Request<T>?) {}
    override fun queue(success: Consumer<T>?, failure: Consumer<Throwable>?) {}
    override fun complete(shouldQueue: Boolean): T? = null
    override fun queueAfter(delay: Long, unit: TimeUnit?, success: Consumer<T>?, failure: Consumer<Throwable>?, executor: ScheduledExecutorService?): ScheduledFuture<*>
    {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(this::class.java.classLoader, arrayOf(ScheduledFuture::class.java), NOPHandler) as ScheduledFuture<*>
    }
    override fun completeAfter(delay: Long, unit: TimeUnit?): T? = null
    override fun submit(shouldQueue: Boolean): RequestFuture<T>
    {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(this::class.java.classLoader, arrayOf(RequestFuture::class.java), NOPHandler) as RequestFuture<T>
    }
    override fun submitAfter(delay: Long, unit: TimeUnit?, executor: ScheduledExecutorService?): ScheduledFuture<T>
    {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(this::class.java.classLoader, arrayOf(ScheduledFuture::class.java), NOPHandler) as ScheduledFuture<T>
    }
}

private class FakeMessageAction(textChannel: TextChannel?): MessageAction(bot, null, textChannel)
{
    override fun handleResponse(response: Response?, request: Request<Message>?) {}
    override fun queue(success: Consumer<Message>?, failure: Consumer<Throwable>?) {}
    override fun complete(shouldQueue: Boolean): Message? = null
    override fun queueAfter(delay: Long, unit: TimeUnit?, success: Consumer<Message>?, failure: Consumer<Throwable>?, executor: ScheduledExecutorService?): ScheduledFuture<*>
    {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(this::class.java.classLoader, arrayOf(ScheduledFuture::class.java), NOPHandler) as ScheduledFuture<*>
    }
    override fun completeAfter(delay: Long, unit: TimeUnit?): Message? = null
    override fun submit(shouldQueue: Boolean): RequestFuture<Message>
    {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(this::class.java.classLoader, arrayOf(RequestFuture::class.java), NOPHandler) as RequestFuture<Message>
    }
    override fun submitAfter(delay: Long, unit: TimeUnit?, executor: ScheduledExecutorService?): ScheduledFuture<Message>
    {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(this::class.java.classLoader, arrayOf(ScheduledFuture::class.java), NOPHandler) as ScheduledFuture<Message>
    }
}

object NOPHandler: InvocationHandler
{
    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? = null
}